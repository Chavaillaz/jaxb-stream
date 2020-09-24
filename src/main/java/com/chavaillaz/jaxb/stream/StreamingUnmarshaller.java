package com.chavaillaz.jaxb.stream;

import lombok.SneakyThrows;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.Closeable;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;

import static com.chavaillaz.jaxb.stream.StreamingMarshaller.getAnnotation;
import static javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD;
import static javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA;
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
 *     unmarshaller.iterate((type, element) -> doSomething(element));
 * </pre>
 * Don't forget to open the stream before trying to read in it.
 */
public class StreamingUnmarshaller implements Closeable {

    private final Map<Class<?>, Unmarshaller> unmarshallerCache;
    private final Map<String, Class<?>> mapType;
    private XMLStreamReader reader;

    /**
     * Creates a new streaming unmarshaller reading elements from the given types
     * Please note that the given classes need the {@link XmlRootElement} annotation.
     *
     * @param types The list of element types that will be read by the unmarshaller
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given types
     * @throws RuntimeException         if an error was encountered while creating the unmarshaller instances
     */
    @SneakyThrows
    public StreamingUnmarshaller(Class<?>... types) {
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
     *
     * @param inputStream The input stream in which read the XML elements
     * @throws RuntimeException if an error was encountered while creating the reader or while skipping tags
     */
    @SneakyThrows
    public void open(InputStream inputStream) {
        XMLInputFactory factory = XMLInputFactory.newInstance();

        // Deny all access to external references
        factory.setProperty(ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(ACCESS_EXTERNAL_SCHEMA, "");
        reader = factory.createXMLStreamReader(inputStream);

        // Ignore headers
        skipElements(START_DOCUMENT, DTD);

        // Ignore root element
        reader.nextTag();

        // If there's no tag, ignore root element's end
        skipElements(END_ELEMENT);
    }

    @SneakyThrows
    protected void skipElements(Integer... elements) {
        int eventType = reader.getEventType();

        List<Integer> types = Arrays.asList(elements);
        while (types.contains(eventType)) {
            eventType = reader.next();
        }
    }

    /**
     * Creates a new unmarshaller for the given type.
     *
     * @param type The type of elements the unmarshaller has to handle
     * @return The unmarshaller created, capable of handling the conversion to the given element type
     * @throws RuntimeException if an error was encountered while creating the unmarshaller
     */
    public Unmarshaller createUnmarshaller(Class<?> type) throws JAXBException {
        return JAXBContext.newInstance(type).createUnmarshaller();
    }

    /**
     * Gets the type of the next element in the stream.
     *
     * @return The next type or {@code null} when not found (in that case, add that type at class instantiation)
     */
    public Class<?> getNextType() {
        return mapType.get(reader.getName().toString());
    }

    /**
     * Reads the next element from the stream.
     *
     * @param type The type of element to read
     * @param <T>  The element type
     * @return The element read from the stream
     * @throws NoSuchElementException   if there's no more element to read
     * @throws IllegalArgumentException if there's a mismatch between the given type and the element type read
     * @throws RuntimeException         if an error was encountered while unmarshalling the element
     */
    @SneakyThrows
    public <T> T next(Class<T> type) {
        if (!hasNext()) {
            throw new NoSuchElementException("There is no more element to read");
        }

        Class<?> nextType = getNextType();
        if (type == null || !type.equals(nextType)) {
            throw new IllegalArgumentException("Mismatch between next type " + nextType + " and given type " + type);
        }

        Unmarshaller unmarshaller = unmarshallerCache.get(type);
        T value = unmarshaller.unmarshal(reader, type).getValue();

        skipElements(CHARACTERS, END_ELEMENT);
        return value;
    }

    /**
     * Indicates if there is one more element to read in the stream.
     *
     * @return {@code true} if there is at least one more element, {@code false} otherwise
     * @throws RuntimeException if an error was encountered while detecting the next state
     */
    @SneakyThrows
    public boolean hasNext() {
        return reader.hasNext();
    }

    /**
     * Iterates over all elements with the given consumer.
     *
     * @param consumer The consumer called for each element of the stream
     */
    public void iterate(BiConsumer<Class<?>, Object> consumer) {
        while (hasNext()) {
            Class<?> type = getNextType();
            consumer.accept(type, next(type));
        }
    }

    /**
     * Closes the stream.
     *
     * @throws RuntimeException if an error was encountered while closing the stream
     */
    @Override
    @SneakyThrows
    public void close() {
        reader.close();
    }

}