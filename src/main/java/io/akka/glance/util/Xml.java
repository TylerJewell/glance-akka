package io.akka.glance.util;

import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * A small tree over an XML document, in the shape Go's {@code encoding/xml} presents one.
 *
 * <p>Elements are addressed by local name and namespaces are ignored, which is how the
 * struct tags the original writes behave for the feeds it reads: {@code media:thumbnail} and
 * {@code thumbnail} are the same element to a caller asking for a thumbnail.
 */
public final class Xml {

  private final String name;
  private final String text;
  private final Map<String, String> attributes;
  private final List<Xml> children;

  private Xml(String name, String text, Map<String, String> attributes, List<Xml> children) {
    this.name = name;
    this.text = text;
    this.attributes = attributes;
    this.children = children;
  }

  public static Xml parse(String source) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var builder = factory.newDocumentBuilder();
      Document document = builder.parse(new org.xml.sax.InputSource(new StringReader(source)));
      return of(document.getDocumentElement());
    } catch (Exception e) {
      throw new Fetches.FetchException(
          Err.of(e.getMessage() == null ? "invalid XML" : e.getMessage()));
    }
  }

  private static Xml of(Element element) {
    var attributes = new LinkedHashMap<String, String>();
    NamedNodeMap map = element.getAttributes();
    for (int i = 0; i < map.getLength(); i++) {
      var attribute = map.item(i);
      attributes.put(localName(attribute.getNodeName()), attribute.getNodeValue());
    }
    var children = new ArrayList<Xml>();
    var text = new StringBuilder();
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      var node = nodes.item(i);
      if (node instanceof Element child) {
        children.add(of(child));
      } else if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE
          || node.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
        text.append(node.getNodeValue());
      }
    }
    return new Xml(
        localName(element.getNodeName()), text.toString(), attributes, List.copyOf(children));
  }

  private static String localName(String name) {
    int colon = name.indexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  public String name() {
    return name;
  }

  public String text() {
    return text;
  }

  public String attribute(String name) {
    return attributes.getOrDefault(name, "");
  }

  public Xml child(String name) {
    for (var child : children) {
      if (child.name.equals(name)) {
        return child;
      }
    }
    return null;
  }

  public List<Xml> children(String name) {
    var out = new ArrayList<Xml>();
    for (var child : children) {
      if (child.name.equals(name)) {
        out.add(child);
      }
    }
    return out;
  }

  public List<Xml> children() {
    return children;
  }

  /** The text of a child element, or the empty string when there is no such child. */
  public String text(String name) {
    var child = child(name);
    return child == null ? "" : child.text();
  }
}
