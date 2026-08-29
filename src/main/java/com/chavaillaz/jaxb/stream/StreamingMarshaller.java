package com.chavaillaz.jaxb.stream;

import static jakarta.xml.bind.Marshaller.JAXB_FRAGMENT;
import static java.lang.Boolean.TRUE;
import static org.codehaus.stax2.XMLOutputFactory2.P_AUTOMATIC_EMPTY_ELEMENTS;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlSchema;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.validation.Schema;

import com.ctc.wstx.stax.WstxOutputFactory;
import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * JAXB marshaller using streaming to write XML into the given output stream.
 * <p>
 * This library allows you to write a list of elements (even from different types, but with same parent) item by item.
 * The goal is to avoid loading a huge amount of data into memory when writing large files.
 * <p>
 * This marshaller works as follows:
 * <ul>
 *     <li>At instantiation, it takes the root element type defining where to store the data (XML container)</li>
 *     <li>When opening the stream, it writes the starting tag of the root element</li>
 *     <li>When writing in the stream, it marshals the given class to XML and store it</li>
 *     <li>When closing the stream, it writes the end tag of the root element</li>
 * </ul>
 * You can use it with:
 * <pre>
 *     marshaller.write(YourObject.class, new YourObject());
 * </pre>
 * Before opening the stream, you can also configure it with {@link #setSchema(Schema)} to validate elements
 * against an XSD schema while writing them, {@link #setCharset(Charset)} to write in a charset other than
 * UTF-8, or {@link #setPrettyPrint(boolean)} to disable the indentation applied by default.
 * <p>
 * For XML files with more than one level of nested containers, use {@link #openChild(String)} to write into
 * a nested container without having to subclass {@link #createDocumentStart()} with the whole structure
 * hardcoded in it.
 * <p>
 * Don't forget to open the stream before trying to write in it.
 */
@Slf4j
public class StreamingMarshaller implements Closeable {

    /**
     * The tag name of the XML container element in which the written elements are stored.
     */
    protected final String rootElement;

    /**
     * The marshaller created for each type already written, keyed by type, so it is only built once.
     */
    private final Map<Class<?>, Marshaller> marshallerCache = new HashMap<>();

    /**
     * The writer used to write the XML document, {@code null} until {@link #open(OutputStream)} is called.
     */
    protected @Nullable XMLStreamWriter xmlWriter;

    /**
     * The output stream given to {@link #open(OutputStream)}, {@code null} until then.
     */
    protected @Nullable OutputStream outputStream;

    /**
     * The charset used to write the XML output, set with {@link #setCharset(Charset)} (UTF-8 by default).
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * Whether the XML output is indented, set with {@link #setPrettyPrint(boolean)} ({@code true} by default).
     */
    private boolean prettyPrint = true;

    /**
     * The schema to validate elements against while marshalling them, set with {@link #setSchema(Schema)},
     * or {@code null} to disable validation (default).
     */
    private @Nullable Schema schema;

    /**
     * Whether this instance is a nested container created by {@link #openChild(String)}: it shares its
     * parent's {@link #xmlWriter} instead of owning one, so {@link #close()} only closes its own container
     * tag instead of the whole document, and {@link #open(OutputStream)} is not allowed on it.
     */
    private boolean childContainer = false;

    /**
     * Creates a new streaming marshaller writing elements in the given root element class.
     * Please note that the given class needs the {@link XmlRootElement} annotation.
     *
     * @param type The root class defining the XML container where to store the elements to write
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given type
     * @throws NullPointerException     if the given type is {@code null}
     */
    public StreamingMarshaller(@NonNull Class<?> type) {
        this.rootElement = getAnnotation(type, XmlRootElement.class).name();
    }

    /**
     * Creates a new streaming marshaller writing elements in the given root element.
     *
     * @param rootElement The root used as XML container where to store the elements to write
     * @throws NullPointerException if the given root element is {@code null}
     */
    public StreamingMarshaller(@NonNull String rootElement) {
        this.rootElement = rootElement;
    }

    /**
     * Gets the given annotation from the given type, failing if it is not present.
     *
     * @param type           The type to look the annotation up on
     * @param annotationType The type of annotation to look up
     * @param <A>            The annotation type
     * @return The annotation instance found on the given type
     * @throws IllegalArgumentException if the given type does not have the given annotation
     */
    protected static <A extends Annotation> A getAnnotation(Class<?> type, Class<A> annotationType) {
        A annotation = type.getAnnotation(annotationType);
        if (annotation == null) {
            throw new IllegalArgumentException("Missing annotation " + annotationType + " in class " + type);
        }
        return annotation;
    }

    /**
     * Gets the effective XML namespace of the given type's {@link XmlRootElement} annotation, resolving
     * {@code "##default"} the same way JAXB itself does: to the namespace declared on the {@link XmlSchema}
     * annotation of the type's package ({@code package-info.java}), or no namespace if there is none.
     *
     * @param type The type to resolve the namespace for
     * @return The effective namespace, or an empty string if the type has none
     * @throws IllegalArgumentException if the given type does not have the {@link XmlRootElement} annotation
     */
    protected static String getNamespace(Class<?> type) {
        String namespace = getAnnotation(type, XmlRootElement.class).namespace();
        if (!"##default".equals(namespace)) {
            return namespace;
        }

        Package pkg = type.getPackage();
        XmlSchema packageSchema = pkg == null ? null : pkg.getAnnotation(XmlSchema.class);
        return packageSchema == null ? "" : packageSchema.namespace();
    }

    /**
     * Opens the given output stream in which the XML file has to be written.
     * It creates the beginning of the document with XML definition and the root element.
     * If an output stream is already open, it closes it before opening the new one.
     * Uses the charset set with {@link #setCharset(Charset)} (UTF-8 by default) and is indented unless
     * disabled with {@link #setPrettyPrint(boolean)}.
     *
     * @param outputStream The output stream in which write the XML elements
     * @throws IllegalStateException if this instance is a nested container returned by {@link #openChild(String)}
     * @throws XMLStreamException    if an error was encountered while starting the XML document with the root
     *                               element, in which case this instance is left in the same not-open state
     *                               as before this call
     */
    public synchronized void open(OutputStream outputStream) throws XMLStreamException {
        if (this.childContainer) {
            throw new IllegalStateException("This marshaller is a nested container returned by openChild(String); "
                    + "it shares its parent's stream and cannot be opened on its own");
        }

        if (this.xmlWriter != null) {
            close();
        }

        this.outputStream = outputStream;
        try {
            WstxOutputFactory wstxOutputFactory = new WstxOutputFactory();
            wstxOutputFactory.setProperty(P_AUTOMATIC_EMPTY_ELEMENTS, true);
            XMLStreamWriter writer = wstxOutputFactory.createXMLStreamWriter(outputStream, this.charset.name());
            this.xmlWriter = this.prettyPrint ? new IndentingXMLStreamWriter(writer) : writer;
            createDocumentStart();
        } catch (XMLStreamException e) {
            closeAfterOpenFailure();
            throw e;
        }
    }

    /**
     * Best-effort cleanup after {@link #open(OutputStream)} failed partway through, so this instance is
     * left in a clean not-open state instead of pretending to be open with a writer that never fully started.
     * Note that the writer itself is only dropped, not closed: {@link XMLStreamWriter#close()} would try to
     * validate and finish a document that was never successfully started, failing with a confusing secondary
     * exception (Woodstox refuses to close a document with no root element). Closing the underlying output
     * stream directly is enough to release the actual resource.
     */
    private void closeAfterOpenFailure() {
        this.xmlWriter = null;
        closeOutputStream();
    }

    /**
     * Creates the beginning of the document (until we reach where to write the stream of elements).
     * Override this method if you have a more complex structure in the XML file to create.
     *
     * @throws XMLStreamException if an error was encountered while starting the XML document with the root element
     */
    protected void createDocumentStart() throws XMLStreamException {
        this.xmlWriter.writeStartDocument();
        this.xmlWriter.writeStartElement(this.rootElement);
    }

    /**
     * Opens a nested container element within this marshaller's own stream, returning a new marshaller that
     * writes into the same underlying stream, wrapped in the given container element. Useful for XML
     * structures with more than one level of nested containers, without having to subclass
     * {@link #createDocumentStart()} with the whole structure hardcoded in it.
     * <p>
     * The returned marshaller has to be closed independently (typically with its own try-with-resources
     * block) before writing anything else with this one. Unlike {@link #close()} on a top-level marshaller,
     * closing it only writes its own closing tag: it does not end the document or close the underlying
     * stream, both of which remain owned by this marshaller (or whichever ancestor originally called
     * {@link #open(OutputStream)}). Nesting can go arbitrarily deep by calling this method again on the
     * marshaller it returns.
     * <p>
     * As with the rest of this class, a marshaller returned by this method is only safe to use from one
     * thread at a time — including with respect to its parent and any siblings, since they all share the
     * same underlying writer.
     *
     * @param name The tag name of the container element to open
     * @return A new marshaller writing into this same stream, nested inside the given container element
     * @throws IllegalStateException if this stream has not been opened yet
     * @throws XMLStreamException    if an error was encountered while writing the container's start tag
     */
    public synchronized StreamingMarshaller openChild(String name) throws XMLStreamException {
        if (this.xmlWriter == null) {
            throw new IllegalStateException("The stream has not been opened yet, please call open(OutputStream) first");
        }

        StreamingMarshaller child = new StreamingMarshaller(name);
        child.xmlWriter = this.xmlWriter;
        child.childContainer = true;
        child.xmlWriter.writeStartElement(name);
        return child;
    }

    /**
     * Writes the given element in XML to the output stream.
     * Please note that the object has to have the {@link XmlRootElement} annotation,
     * otherwise please use the method {@link #write(Class, String, Object)}.
     * The tag name written is qualified with the type's effective namespace (see {@link #getNamespace(Class)})
     * when it has one, matching what JAXB itself would use when marshalling the type on its own.
     *
     * @param type   The type of the given {@code object}
     * @param object The element to marshal and write
     * @param <T>    The element type
     * @throws IllegalStateException    if the stream has not been opened yet
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing on the given type
     * @throws JAXBException            if an error was encountered while marshalling the given object
     */
    public synchronized <T> void write(Class<T> type, T object) throws JAXBException {
        XmlRootElement annotation = getAnnotation(type, XmlRootElement.class);
        QName name = new QName(getNamespace(type), annotation.name());
        write(type, name.toString(), object);
    }

    /**
     * Writes the given element in XML to the output stream.
     * The tag name can be given in the {@code {namespaceURI}localName} format (see {@link QName#valueOf(String)})
     * to write it in a namespace.
     *
     * @param type   The type of the given {@code object}
     * @param name   The tag name of the XML element described in {@link XmlRootElement} or {@link XmlElement}
     * @param object The element to marshal and write
     * @param <T>    The element type
     * @throws IllegalStateException if the stream has not been opened yet
     * @throws JAXBException         if an error was encountered while marshalling the given object
     */
    public synchronized <T> void write(Class<T> type, String name, T object) throws JAXBException {
        if (this.xmlWriter == null) {
            throw new IllegalStateException("The stream has not been opened yet, please call open(OutputStream) first");
        }

        JAXBElement<T> element = new JAXBElement<>(QName.valueOf(name), type, object);
        getMarshaller(type).marshal(element, this.xmlWriter);
    }

    /**
     * Gets the marshaller for the given type.
     *
     * @param type The type of elements the marshaller has to handle
     * @param <T>  The element type
     * @return The marshaller handling the conversion of the given element type
     * @throws JAXBException if an error was encountered while creating the marshaller
     */
    public synchronized <T> Marshaller getMarshaller(Class<T> type) throws JAXBException {
        Marshaller marshaller = this.marshallerCache.get(type);
        if (marshaller == null) {
            marshaller = createMarshaller(type);
            this.marshallerCache.put(type, marshaller);
        }
        return marshaller;
    }

    /**
     * Creates a new marshaller for the given type, applying the schema set with {@link #setSchema(Schema)} if any.
     *
     * @param type The type of elements the marshaller has to handle
     * @return The marshaller created, capable of handling the conversion of the given element type
     * @throws JAXBException if an error was encountered while creating the marshaller
     */
    public Marshaller createMarshaller(Class<?> type) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(type);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(JAXB_FRAGMENT, TRUE);
        if (this.schema != null) {
            marshaller.setSchema(this.schema);
        }
        return marshaller;
    }

    /**
     * Sets the XSD schema to validate elements against while marshalling them.
     * Any marshaller already created and cached for a type is discarded, so this schema takes effect
     * for every type handled by this instance, including ones already written before this call.
     *
     * @param schema The schema to validate against, or {@code null} to disable validation (default)
     */
    public synchronized void setSchema(@Nullable Schema schema) {
        this.schema = schema;
        this.marshallerCache.clear();
    }

    /**
     * Sets the charset used to write the XML output. Has to be called before {@link #open(OutputStream)}.
     *
     * @param charset The charset to use (UTF-8 by default)
     * @throws NullPointerException if the given charset is {@code null}
     */
    public synchronized void setCharset(@NonNull Charset charset) {
        this.charset = charset;
    }

    /**
     * Enables or disables indentation of the XML output. Has to be called before {@link #open(OutputStream)}.
     *
     * @param prettyPrint {@code true} to indent the XML output (default), {@code false} for a compact output
     */
    public synchronized void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }

    /**
     * Closes the stream. For a top-level marshaller, this writes the closing tag, ends the document and
     * closes the underlying output stream given in {@link #open(OutputStream)}. For a nested container
     * returned by {@link #openChild(String)}, this only writes its own closing tag: the document, the
     * underlying writer and the underlying stream all remain open, owned by an ancestor marshaller.
     */
    @Override
    public synchronized void close() {
        try {
            if (this.xmlWriter != null) {
                if (this.childContainer) {
                    this.xmlWriter.writeEndElement();
                } else {
                    this.xmlWriter.writeCharacters("\n");
                    this.xmlWriter.writeEndDocument();
                    this.xmlWriter.close();
                }
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream writer", e);
        } finally {
            this.xmlWriter = null;
            if (!this.childContainer) {
                closeOutputStream();
            }
        }
    }

    private void closeOutputStream() {
        try {
            if (this.outputStream != null) {
                this.outputStream.close();
            }
        } catch (IOException e) {
            log.error("Unable to close underlying output stream", e);
        } finally {
            this.outputStream = null;
        }
    }

}
