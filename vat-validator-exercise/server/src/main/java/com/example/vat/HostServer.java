package com.example.vat;

import com.example.vat.cx.ClientExtensionRegistry;
import com.example.vat.cx.ClientExtensionServlet;
import com.example.vat.proxy.UpstreamRateLimiter;
import com.example.vat.proxy.UpstreamVatClient;
import com.example.vat.proxy.VatLookupServlet;
import com.example.vat.upstream.UpstreamVatServlet;
import com.example.vat.web.CheckoutServlet;

import jakarta.servlet.Servlet;

import java.io.File;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/**
 * Starts the page host and the microservice on one port, and the external VAT
 * registry on the next one up.
 *
 * <p>
 * Everything here is wiring. It is finished, and you should not need to change
 * it except to register something new.
 * </p>
 */
public class HostServer {

	/**
	 * Defaults to 8080. Override with <code>-Pport=8090</code> when something
	 * else already owns it.
	 */
	public static final int PORT = Integer.getInteger("port", 8080);

	public static final int UPSTREAM_PORT = PORT + 1;

	public static void main(String[] args) throws Exception {
		Tomcat tomcat = start(PORT);

		System.out.println();
		System.out.println("  Checkout  http://localhost:" + PORT + "/checkout");
		System.out.println("  Registry  http://localhost:" + UPSTREAM_PORT + "/vat/check?vatId=ESB12345678");
		System.out.println();

		tomcat.getServer(
		).await();
	}

	public static Tomcat start(int port) throws Exception {
		_startUpstream(port + 1);

		Path clientExtensionDir = _clientExtensionDir();

		ClientExtensionRegistry clientExtensionRegistry =
			new ClientExtensionRegistry(clientExtensionDir);

		Tomcat tomcat = _tomcat(port, "vat-host");

		Context context = _context(tomcat, "vat-host");

		_addServlet(
			context, "checkout",
			new CheckoutServlet(clientExtensionRegistry), "/checkout");
		_addServlet(
			context, "clientExtension",
			new ClientExtensionServlet(clientExtensionRegistry),
			ClientExtensionServlet.URI_PREFIX + "*");
		_addServlet(
			context, "vatLookup",
			new VatLookupServlet(
				new UpstreamVatClient(
					"http://localhost:" + (port + 1),
					UpstreamVatServlet.API_KEY),
				new UpstreamRateLimiter()),
			"/o/vat/lookup");

		tomcat.start();

		return tomcat;
	}

	private static void _addServlet(
		Context context, String name, Servlet servlet, String urlPattern) {

		Tomcat.addServlet(context, name, servlet);

		context.addServletMappingDecoded(urlPattern, name);
	}

	private static Path _clientExtensionDir() {
		Path path = Paths.get("client-extension");

		if (path.toFile(
			).isDirectory()) {

			return path.toAbsolutePath();
		}

		return Paths.get("..", "client-extension"
		).toAbsolutePath(
		).normalize();
	}

	private static Context _context(Tomcat tomcat, String name) {
		File baseDir = new File(System.getProperty("java.io.tmpdir"), name);

		baseDir.mkdirs();

		return tomcat.addContext("", baseDir.getAbsolutePath());
	}

	private static void _startUpstream(int port) throws Exception {
		Tomcat tomcat = _tomcat(port, "vat-upstream");

		Context context = _context(tomcat, "vat-upstream");

		_addServlet(
			context, "upstream", new UpstreamVatServlet(), "/vat/check");

		tomcat.start();
	}

	private static Tomcat _tomcat(int port, String name) {
		Tomcat tomcat = new Tomcat();

		File baseDir = new File(
			System.getProperty("java.io.tmpdir"), name + "-" + port);

		baseDir.mkdirs();

		tomcat.setBaseDir(baseDir.getAbsolutePath());
		tomcat.setPort(port);
		tomcat.getConnector();

		return tomcat;
	}

}
