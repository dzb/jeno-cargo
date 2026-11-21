package com.jujin.mjm.pom;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@code pom.xml} into a small element tree. This is the one place
 * mj touches Maven's wire format; everything above works on mj's own model
 * (DESIGN.md D3). The tree is deliberately dumb — traversal and meaning live
 * in {@link Poms}.
 *
 * <p>DTDs and external entities are disabled: poms arrive over the network
 * and never legitimately carry either, so a planted DOCTYPE fails instead of
 * fetching anything.
 */
final class PomXml {

    /**
     * Parse budget for one pom. Central poms stay far below this (the largest
     * BOMs are a few hundred KB); the cap turns a runaway file into an error.
     */
    static final int MAX_INPUT_CHARS = 2 * 1024 * 1024;

    /** Maximum element nesting — real poms nest a dozen levels at most. */
    private static final int MAX_DEPTH = 32;

    private PomXml() {}

    /** Parses one document; the root must be {@code project}. */
    static Node parse(String xml) {
        if (xml == null || xml.length() > MAX_INPUT_CHARS) {
            throw new PomException("pom too large (max " + MAX_INPUT_CHARS + " characters)");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            try {
                Node root = readRoot(reader);
                if (!"project".equals(root.name())) {
                    throw new PomException("pom root element is '" + root.name() + "', expected 'project'");
                }
                return root;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new PomException("pom is not well-formed XML", e);
        }
    }

    private static Node readRoot(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.DTD) {
                // Central poms never carry a DOCTYPE; rejecting it beats
                // skipping it, so a planted reference cannot hide.
                throw new PomException("pom contains a DOCTYPE — DTDs are not allowed");
            }
            if (event == XMLStreamReader.START_ELEMENT) {
                return readElement(reader, 1);
            }
        }
        throw new PomException("pom contains no XML element");
    }

    private static Node readElement(XMLStreamReader reader, int depth) throws XMLStreamException {
        if (depth > MAX_DEPTH) {
            throw new PomException("pom nests deeper than " + MAX_DEPTH + " levels");
        }
        String name = reader.getLocalName();
        StringBuilder text = new StringBuilder();
        List<Node> children = new ArrayList<>();
        while (true) {
            int event = reader.next();
            switch (event) {
                case XMLStreamReader.START_ELEMENT -> children.add(readElement(reader, depth + 1));
                case XMLStreamReader.CHARACTERS, XMLStreamReader.CDATA -> text.append(reader.getText());
                case XMLStreamReader.END_ELEMENT -> {
                    return new Node(name, text.toString().trim(), List.copyOf(children));
                }
                case XMLStreamReader.END_DOCUMENT ->
                        throw new PomException("pom ends inside element '" + name + "'");
                default -> { /* comments, processing instructions, whitespace DTD events: skipped */ }
            }
        }
    }

    /**
     * One element: trimmed text (empty when the element only nests children)
     * and its child elements, in document order.
     */
    record Node(String name, String text, List<Node> children) {

        /** The first child with this name, or {@code null} — for optional single elements. */
        Node first(String name) {
            for (Node child : children) {
                if (child.name().equals(name)) {
                    return child;
                }
            }
            return null;
        }

        /** The first child with this name, or a failure naming the element — for required ones. */
        Node child(String name) {
            Node found = first(name);
            if (found == null) {
                throw new PomException("pom element <" + name + "> is missing");
            }
            return found;
        }

        /** All children with this name, in document order. */
        List<Node> all(String name) {
            List<Node> found = new ArrayList<>();
            for (Node child : children) {
                if (child.name().equals(name)) {
                    found.add(child);
                }
            }
            return List.copyOf(found);
        }

        /** Trimmed text of the first child with this name, or {@code null}. */
        String text(String name) {
            Node found = first(name);
            return found == null ? null : found.text();
        }
    }
}
