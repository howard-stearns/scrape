/* Walk a domain and make a mirror of the files. */
// N.B.: This is an interactive utility, so when it dies, it dies. No need (I hope) to complicate the code with nested tries like:
// BufferedReader in = null; try {...} catch (IOException e) {...} finally { if (in != null) { try { in.close(); } catch (IOException e) {...}}}
import java.net.*;
import java.io.*;
import java.util.*;
// I'm not convinced that the SAX parser in the JDK will handle HTML5, even in the default non-strict mode.
// My test site is XML, so it's ok, but for the general case, I would probably use some open source HTML parser instead of javax sax.
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
// Download from http://commons.apache.org/proper/commons-io/download_io.cgi
import org.apache.commons.io.input.*;

public class Scrape {
    public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.out.println("Usage: java Scrape <rootUrl> <mirrorDir>\ne.g., java -cp *:. Scrape http://junkshop.co mirror");
			return;
		}
		new Scrape(new URL(args[0]), args[1] + "/");
    }
	protected String host;             // So we can reject links to other hosts.
	protected String mirrorDirectory;  // Where we will keep our mirror copy. 
	protected Map<URL, String> seen = new HashMap<URL, String>(); // So that we don't repeat ourselves.
	protected SAXParserFactory factory = SAXParserFactory.newInstance();
	public static final String start = "start";
	public static final String done = "done";
	public Scrape(URL root, String mirrorDirectory) {
		this.host = root.getHost();
		this.mirrorDirectory = mirrorDirectory;
		walk(root);
	}
	protected void xmlParse(URL uri, InputStream in) throws Exception {
		final URL resolvedUri = uri;
		SAXParser parser = factory.newSAXParser(); 
		// Does HttpURLConnection respect charset in the content-type header? 
		// If not a production system would need to take care of that here.
		//InputStream tee = new TeeInputStream(in, out); // pipe the input steam to both the out file and the parser.
		parser.parse(in, new DefaultHandler() {
				// Note that we're streaming the data directly to the mirror location without translation.
				// This means that:
				// 1. absolute URL references in the mirror will still point to the original site
				// 2. relative URL references in the mirror will resolve in the browser to the root of the file system, rather than within the mirror directory.
				public void error(SAXParseException e) { System.out.println("error " + resolvedUri + " " + e); }
				public void fatalError(SAXParseException e) { System.out.println("critical " + resolvedUri + " " + e); }
				public void warning(SAXParseException e) { System.out.println("warning " + resolvedUri + " " + e); }
				public void startElement(String uri, String localName, String qName, Attributes attributes) {
					// For our purposes, we don't care about the node tag name: we do care about recursing through href and src links.
					// However, for sites other than mine, there could be a meta tag that changes content-type or charset,
					// or the xml declaration could change encoding. Not bothering here.
					String ref = attributes.getValue("href"); // pre HTML4 allows uppercase attributes. I bet SAX doesn't normalize...
					if (ref == null) ref = attributes.getValue("src");
					if (ref != null) {
						try { // Apparently outer try/catch has some odd semantics with respect to inner classes, so this try/catch is necessary.
							// If it turns out that deeply nesting these blows the Java call stack, we could instead
							// place the new url on a list to be processed serially.
							walk(new URL(resolvedUri, ref));
						} catch (MalformedURLException e) {
							System.out.println(e); 
						}
					}
				}
			});
	}
	public void walk(URL uri) {
		if (seen.containsKey(uri) || (host != uri.getHost())) { return; }
		try {
			seen.put(uri, start); 
			HttpURLConnection connection = (HttpURLConnection) uri.openConnection();
			InputStream in = connection.getInputStream();
			final URL resolvedUri = connection.getURL(); // e.g., if redirected. Must be after connect(), or after getInputStream() waits on a connect().
			File mirrored = new File(mirrorDirectory, resolvedUri.getPath());
			mirrored.getParentFile().mkdirs();
			FileOutputStream out = new FileOutputStream(mirrored);
			int nRead = 0;
			byte[] buffer = new byte[8192];
			switch (connection.getResponseCode()) {
				// It turns out that HttpURLConnection follows redirects so we don't have to worry about 302 here.
				// I suppose a true mirror would catch this and create a file system link from the path to the header location path.
				// We could also consider 301, HTML>HEAD>META[http-equiv=refresh], or scripts that set document.location.href.
			case 200:
				if (connection.getHeaderField("content-type").startsWith("text/html")) {
					//xmlParse(resolvedUri, new TeeInputStream(in, out));
					while ((nRead = in.read(buffer)) != -1) {
						System.out.println("read " + nRead + " bytes.");
						
						out.write(buffer, 0, nRead);
					}
				} else { // css, images, etc. Just copy the bytes.
					while ((nRead = in.read(buffer)) != -1) {
						out.write(buffer, 0, nRead);
					}
				}
				break;
			default: // Anything else is an error.
				System.out.println(resolvedUri.toString() + " " + connection.getResponseCode());
			}
			in.close(); 
			out.close();
			System.out.println("done: " + uri);
			seen.put(uri, done);
		} catch (Exception e) {
			System.out.println("error " + uri + " " + e);
		}
	}
}
