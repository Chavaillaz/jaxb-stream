package com.chavaillaz.jaxb.stream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.validation.Schema;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.chavaillaz.jaxb.stream.StreamingMarshaller.getAnnotation;
import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;
import static javax.xml.stream.XMLStreamConstants.*;

/**
 * JAXB unmarshaller using streaming to read XML from the given input stream.
 * <p>
 * This library allows you to extract a list of elements (even from different types, but with same parent) item by item.
 * The goal is to avoid loading a huge amount of data into memory when reading large files.
 * <p>
 * This unmarshaller works the following way:
 * <ul>
 *     <li>At instantiation, it takes the types of elements to be read (not the root element)</li>
 *     <li>When opening the stream, it reads (ignore) the starting tag of the root element</li>
 *     <li>When getting the next stream element, it unmarshals it from XML to the given object type</li>
 * </ul>
 * You can use with the {@link #next(Class)} method:
 * <pre>
 *     while (unmarshaller.hasNext()) {
 *         unmarshaller.next(YourObject.class);
 *     }
 * </pre>
 * or with the {@link #iterate(BiConsumer)} method:
 * <pre>
 *     unmarshaller.iterate((type, element) -&gt; doSomething(element));
 * </pre>
 * or, since it is also {@link Iterable}, with a for-each loop or the {@link #stream()} method:
 * <pre>
 *     for (Object element : unmarshaller) {
 *         doSomething(element);
 *     }
 *     unmarshaller.stream().forEach(element -&gt; doSomething(element));
 * </pre>
 * Don't forget to open the stream before trying to read in it.
 */
@Slf4j
public class StreamingUnmarshaller implements Closeable, Iterable<Object> {

    private final Map<Class<?>, Unmarshaller> unmarshallerCache = new HashMap<>();
    private final Map<String, Class<?>> mapType = new HashMap<>();
    private @Nullable XMLStreamReader xmlReader;
    private @Nullable InputStream inputStream;
    private @Nullable Schema schema;

    /**
     * Creates a new streaming unmarshaller reading elements from the given types.
     * Please note that the given classes need the {@link XmlRootElement} annotation.
     * The underlying JAXB {@link Unmarshaller} for each type is created lazily, on first use.
     *
     * @param types The list of element types that will be read by the unmarshaller
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given types
     */
    public StreamingUnmarshaller(Class<?>... types) {
        for (Class<?> type : types) {
            String key = getAnnotation(type, XmlRootElement.class).name();
            this.mapType.put(key, type);
        }
    }

    /**
     * Creates a new streaming unmarshaller reading elements from the given types.
     * Please note that the {@link Map} has to contain each type with its XML tag name
     * (equivalent to the value in {@link XmlRootElement} or {@link XmlElement})
     * The underlying JAXB {@link Unmarshaller} for each type is created lazily, on first use.
     *
     * @param types The list of elements types with their name that will be read by the unmarshaller
     */
    public StreamingUnmarshaller(Map<Class<?>, String> types) {
        for (Map.Entry<Class<?>, String> entry : types.entrySet()) {
            this.mapType.put(entry.getValue(), entry.getKey());
        }
    }

    /**
     * Opens the given input stream in which the XML file has to be read.
     * It skips the beginning of the document with XML definition and the root element (container tag).
     * If an input stream is already open, it closes it before opening the new one.
     *
     * @param inputStream The input stream in which read the XML elements
     * @throws XMLStreamException if an error was encountered while creating the reader or while skipping tags
     */
    public synchronized void open(InputStream inputStream) throws XMLStreamException {
        open(inputStream, 1);
    }

    /**
     * Opens the given input stream in which the XML file has to be read.
     * It skips the beginning of the document with XML definition and a number of container tags
     * (putting 1 as {@code skipDepth} corresponds to only skip the root element).
     * If an input stream is already open, it closes it before opening the new one.
     *
     * @param inputStream The input stream in which read the XML elements
     * @param skipDepth   The number of container to skip before reaching the stream of desired elements
     * @throws XMLStreamException if an error was encountered while creating the reader or while skipping tags,
     *                            in which case this instance is left in the same not-open state as before this call
     */
    public synchronized void open(InputStream inputStream, int skipDepth) throws XMLStreamException {
        if (this.xmlReader != null) {
            close();
        }

        this.inputStream = inputStream;
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            // Deny all access to external references
            factory.setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(SUPPORT_DTD, false);
            this.xmlReader = factory.createXMLStreamReader(inputStream);
            skipDocumentStart(skipDepth);
        } catch (XMLStreamException e) {
            closeAfterOpenFailure();
            throw e;
        }
    }

    /**
     * Best-effort cleanup after {@link #open(InputStream, int)} failed partway through, so this instance is
     * left in a clean not-open state instead of pretending to be open with a reader that never fully started.
     */
    private void closeAfterOpenFailure() {
        try {
            if (this.xmlReader != null) {
                this.xmlReader.close();
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream reader after open() failed", e);
        } finally {
            this.xmlReader = null;
            closeInputStream();
        }
    }

    /**
     * Skip the elements at the start of the document to reach the list to browse.
     * Override this method if you have a complex structure in the XML file before reaching the elements list.
     * Note that the parameter {@code skipDepth} may become irrelevant when reimplementing it depending on the
     * file structure complexity.
     *
     * @param skipDepth The number of containers to skip before reaching the stream of desired elements
     * @throws XMLStreamException if an error was encountered while skipping tags
     */
    protected void skipDocumentStart(int skipDepth) throws XMLStreamException {
        // Ignore headers
        skipElements(START_DOCUMENT, DTD);

        for (int i = 0; i < skipDepth; ++i) {
            // Ignore root element
            this.xmlReader.nextTag();
        }

        // If there's no tag, ignore root element's end
        skipElements(END_ELEMENT);
    }

    /**
     * Skips the given event types.
     *
     * @param elements The event types to ignore
     * @throws XMLStreamException if an error was encountered while skipping the elements
     */
    protected void skipElements(Integer... elements) throws XMLStreamException {
        int eventType = this.xmlReader.getEventType();

        List<Integer> types = Arrays.asList(elements);
        while (types.contains(eventType)) {
            eventType = this.xmlReader.next();
        }
    }

    /**
     * Sets the XSD schema to validate elements against while unmarshalling them.
     * Any unmarshaller already created and cached for a type is discarded, so this schema
     * takes effect for every type handled by this instance, including ones already read before this call.
     *
     * @param schema The schema to validate against, or {@code null} to disable validation (default)
     */
    public synchronized void setSchema(@Nullable Schema schema) {
        this.schema = schema;
        this.unmarshallerCache.clear();
    }

    /**
     * Creates a new unmarshaller for the given type, applying the schema set with {@link #setSchema(Schema)} if any.
     *
     * @param type The type of elements the unmarshaller has to handle
     * @return The unmarshaller created, capable of handling the conversion to the given element type
     * @throws JAXBException if an error was encountered while creating the unmarshaller
     */
    public Unmarshaller createUnmarshaller(Class<?> type) throws JAXBException {
        Unmarshaller unmarshaller = JAXBContext.newInstance(type).createUnmarshaller();
        if (this.schema != null) {
            unmarshaller.setSchema(this.schema);
        }
        return unmarshaller;
    }

    /**
     * Gets the unmarshaller for the given type, creating and caching it on first use.
     *
     * @param type The type of elements the unmarshaller has to handle
     * @return The unmarshaller handling the conversion of the given element type
     * @throws JAXBException if an error was encountered while creating the unmarshaller
     */
    public synchronized Unmarshaller getUnmarshaller(Class<?> type) throws JAXBException {
        Unmarshaller unmarshaller = this.unmarshallerCache.get(type);
        if (unmarshaller == null) {
            unmarshaller = createUnmarshaller(type);
            this.unmarshallerCache.put(type, unmarshaller);
        }
        return unmarshaller;
    }

    /**
     * Gets the type of the next element in the stream.
     *
     * @return The next type
     * @throws IllegalStateException if the stream has not been opened yet
     * @throws XMLStreamException    if there's no more element to read, or if the next element's tag name is not
     *                               one of the types given at instantiation (or the {@code skipDepth} parameter
     *                               given to {@link #open(InputStream, int)} does not match the file structure)
     */
    public synchronized Class<?> getNextType() throws XMLStreamException {
        if (!hasNext()) {
            throw new XMLStreamException("There is no more element to read");
        }

        return Optional.ofNullable(this.xmlReader)
                .map(XMLStreamReader::getName)
                .map(QName::toString)
                .map(this.mapType::get)
                .orElseThrow(() -> new XMLStreamException("Unknown next type in the stream, " +
                        "check given ones in constructor or if skipDepth parameter in open method is correct"));
    }

    /**
     * Reads the next element from the stream.
     *
     * @param type The type of element to read, or {@code null} (always treated as a mismatch, see below)
     * @param <T>  The element type
     * @return The element read from the stream
     * @throws IllegalStateException if the stream has not been opened yet
     * @throws XMLStreamException    if there's no more element to read
     * @throws JAXBException         if there's a mismatch between the given type and the element type read,
     *                               including when the given type is {@code null}
     * @throws JAXBException         if an error was encountered while unmarshalling the element
     */
    public synchronized <T> T next(@Nullable Class<T> type) throws JAXBException, XMLStreamException {
        Class<?> nextType = getNextType();
        if (type == null || !type.equals(nextType)) {
            throw new JAXBException("Mismatch between next type " + nextType + " and given type " + type);
        }

        Unmarshaller unmarshaller = getUnmarshaller(type);
        T value = unmarshaller.unmarshal(this.xmlReader, type).getValue();

        skipElements(CHARACTERS, END_ELEMENT);
        return value;
    }

    /**
     * Indicates if there is one more element to read in the stream.
     *
     * @return {@code true} if there is at least one more element, {@code false} otherwise
     * @throws IllegalStateException if the stream has not been opened yet
     * @throws XMLStreamException    if an error was encountered while detecting the next state
     */
    public synchronized boolean hasNext() throws XMLStreamException {
        if (this.xmlReader == null) {
            throw new IllegalStateException("The stream has not been opened yet, please call open(InputStream) first");
        }
        return this.xmlReader.hasNext();
    }

    /**
     * Iterates over all elements with the given consumer.
     *
     * @param consumer The consumer called for each element of the stream
     * @throws IllegalStateException if the stream has not been opened yet
     * @throws XMLStreamException    if an error was encountered while detecting the next state
     * @throws JAXBException         if an error was encountered while unmarshalling an element
     */
    public synchronized void iterate(BiConsumer<Class<?>, Object> consumer) throws JAXBException, XMLStreamException {
        while (hasNext()) {
            Class<?> type = getNextType();
            consumer.accept(type, next(type));
        }
    }

    /**
     * Returns an iterator over the remaining elements of the stream, allowing this unmarshaller to be used
     * in a for-each loop. Checked exceptions ({@link XMLStreamException}, {@link JAXBException}) raised while
     * reading are wrapped in an {@link UncheckedXmlException}, since {@link Iterator} methods cannot throw them.
     *
     * @return An iterator unmarshalling one element per call to {@link Iterator#next()}
     */
    @Override
    public Iterator<Object> iterator() {
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                try {
                    return StreamingUnmarshaller.this.hasNext();
                } catch (XMLStreamException e) {
                    throw new UncheckedXmlException(e);
                }
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                try {
                    return StreamingUnmarshaller.this.next(getNextType());
                } catch (JAXBException | XMLStreamException e) {
                    throw new UncheckedXmlException(e);
                }
            }

        };
    }

    /**
     * Returns a sequential {@link Stream} over the remaining elements of the stream.
     * Checked exceptions ({@link XMLStreamException}, {@link JAXBException}) raised while reading
     * are wrapped in an {@link UncheckedXmlException}, since {@link Stream} operations cannot throw them.
     *
     * @return A stream unmarshalling one element per element consumed from it
     */
    public Stream<Object> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    /**
     * Closes the stream and the underlying input stream given in {@link #open(InputStream)}.
     */
    @Override
    public synchronized void close() {
        try {
            if (this.xmlReader != null) {
                this.xmlReader.close();
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream reader", e);
        } finally {
            this.xmlReader = null;
            closeInputStream();
        }
    }

    private void closeInputStream() {
        try {
            if (this.inputStream != null) {
                this.inputStream.close();
            }
        } catch (IOException e) {
            log.error("Unable to close underlying input stream", e);
        } finally {
            this.inputStream = null;
        }
    }

}
