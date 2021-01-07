package com.chavaillaz.jaxb.stream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Closeable;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static com.chavaillaz.jaxb.stream.StreamingMarshaller.getAnnotation;
import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;
import static javax.xml.stream.XMLStreamConstants.*;

/**
 * JAXB unmarshaller using streaming to read XML from the given output stream.
 * <p>
 * This library allows you to extract a list of elements (even from different types, but with same parent) item by item.
 * The goal is to avoid loading a huge amount of data into memory when writing large files.
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
 * Don't forget to open the stream before trying to read in it.
 */
@Slf4j
public class StreamingUnmarshaller implements Closeable {

    private final Map<Class<?>, Unmarshaller> unmarshallerCache;
    private final Map<String, Class<?>> mapType;
    private XMLStreamReader xmlReader;

    /**
     * Creates a new streaming unmarshaller reading elements from the given types.
     * Please note that the given classes need the {@link XmlRootElement} annotation.
     *
     * @param types The list of element types that will be read by the unmarshaller
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given types
     * @throws JAXBException            if an error was encountered while creating the unmarshaller instances
     */
    public StreamingUnmarshaller(Class<?>... types) throws JAXBException {
        unmarshallerCache = new HashMap<>();
        mapType = new HashMap<>();

        for (Class<?> type : types) {
            String key = getAnnotation(type, XmlRootElement.class).name();
            unmarshallerCache.put(type, createUnmarshaller(type));
            mapType.put(key, type);
        }
    }

    /**
     * Opens the given input stream in which the XML file has to be read.
     * It skips the beginning of the document with XML definition and the root element.
     * If an input stream is already open, it closes it before opening the new one.
     *
     * @param inputStream The input stream in which read the XML elements
     * @throws XMLStreamException if an error was encountered while creating the reader or while skipping tags
     */
    public synchronized void open(InputStream inputStream) throws XMLStreamException {
        if (xmlReader != null) {
            close();
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Deny all access to external references
        factory.setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(SUPPORT_DTD, false);
        xmlReader = factory.createXMLStreamReader(inputStream);

        // Ignore headers
        skipElements(START_DOCUMENT, DTD);

        // Ignore root element
        xmlReader.nextTag();

        // If there's no tag, ignore root element's end
        skipElements(END_ELEMENT);
    }

    protected void skipElements(Integer... elements) throws XMLStreamException {
        int eventType = xmlReader.getEventType();

        List<Integer> types = Arrays.asList(elements);
        while (types.contains(eventType)) {
            eventType = xmlReader.next();
        }
    }

    /**
     * Creates a new unmarshaller for the given type.
     *
     * @param type The type of elements the unmarshaller has to handle
     * @return The unmarshaller created, capable of handling the conversion to the given element type
     * @throws JAXBException if an error was encountered while creating the unmarshaller
     */
    public Unmarshaller createUnmarshaller(Class<?> type) throws JAXBException {
        return JAXBContext.newInstance(type).createUnmarshaller();
    }

    /**
     * Gets the type of the next element in the stream.
     *
     * @return The next type or {@code null} when not found (in that case, add that type at class instantiation)
     * @throws XMLStreamException if an error was encountered while detecting the next state
     */
    public Class<?> getNextType() throws XMLStreamException {
        if (!hasNext()) {
            throw new XMLStreamException("There is no more element to read");
        }

        return mapType.get(xmlReader.getName().toString());
    }

    /**
     * Reads the next element from the stream.
     *
     * @param type The type of element to read
     * @param <T>  The element type
     * @return The element read from the stream
     * @throws XMLStreamException if there's no more element to read
     * @throws JAXBException      if there's a mismatch between the given type and the element type read
     * @throws JAXBException      if an error was encountered while unmarshalling the element
     */
    public synchronized <T> T next(Class<T> type) throws JAXBException, XMLStreamException {
        Class<?> nextType = getNextType();
        if (type == null || !type.equals(nextType)) {
            throw new JAXBException("Mismatch between next type " + nextType + " and given type " + type);
        }

        Unmarshaller unmarshaller = unmarshallerCache.get(type);
        T value = unmarshaller.unmarshal(xmlReader, type).getValue();

        skipElements(CHARACTERS, END_ELEMENT);
        return value;
    }

    /**
     * Indicates if there is one more element to read in the stream.
     *
     * @return {@code true} if there is at least one more element, {@code false} otherwise
     * @throws XMLStreamException if an error was encountered while detecting the next state
     */
    public boolean hasNext() throws XMLStreamException {
        return xmlReader.hasNext();
    }

    /**
     * Iterates over all elements with the given consumer.
     *
     * @param consumer The consumer called for each element of the stream
     * @throws XMLStreamException if an error was encountered while detecting the next state
     * @throws JAXBException      if an error was encountered while unmarshalling an element
     */
    public void iterate(BiConsumer<Class<?>, Object> consumer) throws JAXBException, XMLStreamException {
        while (hasNext()) {
            Class<?> type = getNextType();
            consumer.accept(type, next(type));
        }
    }

    /**
     * Closes the stream.
     */
    @Override
    public synchronized void close() {
        try {
            if (xmlReader != null) {
                xmlReader.close();
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream reader", e);
        } finally {
            xmlReader = null;
        }
    }

}