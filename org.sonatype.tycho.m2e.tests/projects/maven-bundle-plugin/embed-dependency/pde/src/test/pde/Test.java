package test.pde;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.XMLParser;

public class Test {
	public void test() throws Exception {
		Document document = XMLParser.parse("<for bar='munchy'/>");
	}
}
