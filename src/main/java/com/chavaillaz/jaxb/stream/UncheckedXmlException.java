package com.chavaillaz.jaxb.stream;

/**
 * Wraps a {@link jakarta.xml.bind.JAXBException} or {@link javax.xml.stream.XMLStreamException} as an
 * unchecked exception, since neither {@link java.util.Iterator} nor {@link java.util.stream.Stream} allow
 * their methods to throw checked exceptions.
 *
 * @see StreamingUnmarshaller#iterator()
 * @see StreamingUnmarshaller#stream()
 */
public class UncheckedXmlException extends RuntimeException {

    /**
     * Creates a new unchecked exception wrapping the given checked exception.
     *
     * @param cause The {@link jakarta.xml.bind.JAXBException} or {@link javax.xml.stream.XMLStreamException} to wrap
     */
    public UncheckedXmlException(Exception cause) {
        super(cause);
    }

}
