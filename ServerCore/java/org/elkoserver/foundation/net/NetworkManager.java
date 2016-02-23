package org.elkoserver.foundation.net;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.elkoserver.foundation.boot.BootProperties;
import org.elkoserver.foundation.run.Runner;
import org.elkoserver.json.JSONLiteral;
import org.elkoserver.util.trace.Trace;

/**
 * Manage network connections between this server and other entities.
 */
public class NetworkManager {
    /** Maximum length message that a connection will be able to receive. */
    static public final int MAX_MSG_LENGTH = 1024*1024;

    /**
     * Special debug support: if this flag is set to true, errors generated by
     * received messages will result in a 'debug' message being sent in reply.
     * Normally (when the flag is false), from the sender's perspective such
     * errors will be silently swallowed.
     */
    static public boolean TheDebugReplyFlag = false;

    /** Connection count tracker. */
    private ConnectionCountMonitor myConnectionCountMonitor;

    /** This server's boot properties, for initializing network access. */
    private BootProperties myProps;

    /** The run queue for this server. */
    private Runner myRunner;

    /** System load tracker. */
    private LoadMonitor myLoadMonitor;

    /** Initialized SSL context, if supporting SSL, else null. */
    private SSLContext mySSLContext;

    /** Select thread for non-blocking I/O. */
    private SelectThread mySelectThread;

    /** Connection managers, indexed by class name. */
    private Map<String, ConnectionManager> myConnectionManagers;

    /**
     * Construct a NetworkManager for this server.
     *
     * @param connectionCountMonitor  Monitor for tracking session count.
     * @param props  Boot properties for this server.
     * @param loadMonitor  Load monitor for tracking system load.
     * @param runner  The Runner managing this server's run queue.
     */
    public NetworkManager(ConnectionCountMonitor connectionCountMonitor,
                          BootProperties props, LoadMonitor loadMonitor,
                          Runner runner)
    {
        myConnectionCountMonitor = connectionCountMonitor;
        myProps = props;
        myRunner = runner;
        myConnectionManagers = new HashMap<String, ConnectionManager>();
        HTTPSessionConnection.initializeRNG();
        myLoadMonitor = loadMonitor;
        mySelectThread = null;

        boolean jsonStrictness =
            props.boolProperty("conf.comm.jsonstrictness", true);
        JSONLiteral.setStrictness(jsonStrictness);

        if (props.testProperty("conf.ssl.enable")) {
            setupSSL();
        }
    }

    /**
     * Keep track of the number of connections.
     *
     * @param adjust  Adjustment being made to the number of active
     *    connections, plus or minus.
     */
    public void connectionCount(int adjust) {
        myConnectionCountMonitor.connectionCountChange(adjust);
    }

    /**
     * Obtain the connection manager associated with a particular connection
     * manager class, either by retrieving it or creating it as needed.
     *
     * @param connectionManagerClassName  Fully qualified class name of the
     *    connection manager class desired.
     * @param msgTrace  Trace object for logging message traffic.
     *
     * @return the connection manager with the given class name, or null if no
     *    such connection manager is available.
     */
    private ConnectionManager connectionManager(String className,
                                                Trace msgTrace)
    {
        ConnectionManager result = myConnectionManagers.get(className);
        if (result == null) {
            String problem = null;
            try {
                result =
                    (ConnectionManager) Class.forName(className).newInstance();
                result.init(this, msgTrace);
                myConnectionManagers.put(className, result);
            } catch (ClassNotFoundException e) {
                Trace.comm.errorm("ConnectionManager class " + className +
                                  " not found: " + e);
            } catch (InstantiationException e) {
                Trace.comm.errorm("ConnectionManager class " + className +
                                  " not instantiable: " + e);
            } catch (IllegalAccessException e) {
                Trace.comm.errorm("ConnectionManager class " + className +
                                  " constructor not accessible: " + e);
            }
        }
        return result;
    }

    /**
     * Make a TCP connection to another host given a host:port address.
     *
     * @param hostPort  The host name (or IP address) and port to connect to,
     *    separated by a colon.  For example, "bithlo.example.com:8002".
     * @param handlerFactory  Message handler factory to provide the handler
     *    for the connection that results from this operation.
     * @param framerFactory  Byte I/O framer factory for the new connection.
     * @param trace  Trace object to use for activity on the new connection.
     */
    public void connectTCP(String hostPort,
                           MessageHandlerFactory handlerFactory,
                           ByteIOFramerFactory framerFactory, Trace trace)
    {
        connectionCount(1);
        ensureSelectThread();
        mySelectThread.connect(handlerFactory, framerFactory, hostPort, trace);
    }

    /**
     * Make a connection to another host given a host:port address using a
     * named connection manager class.
     *
     * @param connectionManagerClassName  Fully qualified class name of the
     *    connection manager class to use to make this connection.
     * @param propRoot  Prefix string for all the properties describing the
     *    connection that is to be made.
     * @param hostPort  The host name (or IP address) and port to connect to,
     *    separated by a colon.  For example, "bithlo.example.com:8002".
     * @param handlerFactory  Message handler factory to provide the handler
     *    for the connection that results from this operation.
     * @param msgTrace  Trace object for logging message traffic.
     */
    public void connectVia(String connectionManagerClassName,
                           String propRoot,
                           String hostPort,
                           MessageHandlerFactory handlerFactory,
                           Trace msgTrace)
    {
        ConnectionManager connMgr =
            connectionManager(connectionManagerClassName, msgTrace);
        if (connMgr == null) {
            handlerFactory.provideMessageHandler(null);
        } else {
            connectionCount(1);
            connMgr.connect(propRoot, handlerFactory, hostPort);
        }
    }

    /**
     * Start the select thread if it's not already running.
     */
    private void ensureSelectThread() {
        if (mySelectThread == null) {
            mySelectThread = new SelectThread(this, mySSLContext);
        }
    }

    /**
     * Begin listening for incoming HTTP connections on some port.
     *
     * @param listenAddress  Host name and port to listen for connections on.
     * @param innerHandlerFactory  Message handler factory to provide message
     *    handlers for messages passed inside HTTP requests on connections made
     *    to this port.
     * @param rootURI  The root URI that GETs and POSTs must reference.
     * @param httpFramer  HTTP framer to interpret HTTP POSTs and format HTTP
     *    replies.
     * @param secure  If true, use SSL.
     * @param trace  Trace object to use for activity on this connection.
     *
     * @return the address that ended up being listened upon
     */
    public NetAddr listenHTTP(String listenAddress,
                              MessageHandlerFactory innerHandlerFactory,
                              String rootURI, HTTPFramer httpFramer,
                              boolean secure, Trace trace)
        throws IOException
    {
        MessageHandlerFactory outerHandlerFactory =
            new HTTPMessageHandlerFactory(
               innerHandlerFactory, rootURI, httpFramer, this);

        ByteIOFramerFactory framerFactory =
            new HTTPRequestByteIOFramerFactory();

        return listenTCP(listenAddress, outerHandlerFactory, framerFactory,
                         secure, trace);
    }

    /**
     * Begin listening for incoming RTCP connections on some port.
     *
     * @param listenAddress  Host name and port to listen for connections on.
     * @param innerHandlerFactory  Message handler factory to provide message
     *    handlers for messages passed inside RTCP requests on connections made
     *    to this port.
     * @param msgTrace   Trace object for logging message traffice
     * @param secure  If true, use SSL.
     *
     * @return the address that ended up being listened upon
     */
    public NetAddr listenRTCP(String listenAddress,
                              MessageHandlerFactory innerHandlerFactory,
                              Trace msgTrace,
                              boolean secure)
        throws IOException
    {
        MessageHandlerFactory outerHandlerFactory =
            new RTCPMessageHandlerFactory(innerHandlerFactory, msgTrace, this);

        ByteIOFramerFactory framerFactory =
            new RTCPRequestByteIOFramerFactory(msgTrace);

        return listenTCP(listenAddress, outerHandlerFactory, framerFactory,
                         secure, msgTrace);
    }

    /**
     * Begin listening for incoming WebSocket connections on some port.
     *
     * @param listenAddress  Host name and port to listen for connections on.
     * @param innerHandlerFactory  Message handler factory to provide message
     *    handlers for messages passed inside WebSocket frames on connections
     *    made to this port.
     * @param socketURI  The WebSocket URI that browsers connect to
     * @param msgTrace   Trace object for logging message traffice
     * @param secure  If true, use SSL.
     *
     * @return the address that ended up being listened upon
     */
    public NetAddr listenWebSocket(String listenAddress,
                                   MessageHandlerFactory innerHandlerFactory,
                                   String socketURI,
                                   Trace msgTrace,
                                   boolean secure)
        throws IOException
    {
        if (!socketURI.startsWith("/")) {
            socketURI = "/" + socketURI;
        }
        MessageHandlerFactory outerHandlerFactory =
            new WebSocketMessageHandlerFactory(innerHandlerFactory, socketURI,
                                               msgTrace, this);

        ByteIOFramerFactory framerFactory =
            new WebSocketByteIOFramerFactory(msgTrace, listenAddress, socketURI);

        return listenTCP(listenAddress, outerHandlerFactory, framerFactory,
                         secure, msgTrace);
    }

    /**
     * Begin listening for incoming TCP connections on some port.
     *
     * @param listenAddress  Host name and port to listen for connections on.
     * @param handlerFactory  Message handler factory to provide message
     *    handlers for connections made to this port.
     * @param framerFactory  Byte I/O framer factory for the new connection.
     * @param secure  If true, use SSL.
     * @param portTrace  Trace object for logging activity associated with this
     *   port & its connections
     *
     * @return the address that ended up being listened upon
     */
    public NetAddr listenTCP(String listenAddress,
                             MessageHandlerFactory handlerFactory,
                             ByteIOFramerFactory framerFactory, boolean secure,
                             Trace portTrace)
        throws IOException
    {
        ensureSelectThread();
        Listener listener =
            mySelectThread.listen(listenAddress, handlerFactory,
                                  framerFactory, secure, portTrace);
        return listener.listenAddress();
    }

    /**
     * Begin listening for incoming connections on some port using a named
     * connection manager class.
     *
     * @param connectionManagerClassName  Fully qualified class name of the
     *    connection manager class to use to make this connection.
     * @param propRoot  Prefix string for all the properties describing the
     *    listener that is to be started.
     * @param listenAddress  Host name and port to listen for connections on.
     * @param handlerFactory  Message handler factory to provide message
     *    handlers for connections made to this port.
     * @param msgTrace  Trace object for logging message traffic.
     * @param secure  If true, use a secure connection pathway (e.g., SSL).
     *
     * @return the address that ended up being listened upon
     *
     * @throws IOException if the requested connection manager was unavailable.
     */
    public NetAddr listenVia(String connectionManagerClassName,
                             String propRoot,
                             String listenAddress,
                             MessageHandlerFactory handlerFactory,
                             Trace msgTrace,
                             boolean secure)
        throws IOException
    {
        ConnectionManager connMgr =
            connectionManager(connectionManagerClassName, msgTrace);
        if (connMgr == null) {
            throw new IOException("no connection manager " +
                                  connectionManagerClassName);
        }
        return connMgr.listen(propRoot, listenAddress, handlerFactory, secure);
    }

    /**
     * Get the load monitor for this server.
     *
     * @return the load monitor.
     */
    LoadMonitor loadMonitor() {
        return myLoadMonitor;
    }

    /**
     * Get the run queue for this server.
     *
     * @return the current runner.
     */
    Runner runner() {
        return myRunner;
    }

    /**
     * Get this server's properties.
     *
     * @return the properties
     */
    public BootProperties props() {
        return myProps;
    }

    /**
     * Do all the various key and certificate management stuff needed to set
     * up to support SSL connections.
     */
    private void setupSSL() {
        try {
            String keyStoreType =
                myProps.getProperty("conf.ssl.keystoretype", "JKS");
            KeyStore keys = KeyStore.getInstance(keyStoreType);

            String keyPassword = myProps.getProperty("conf.ssl.keypassword");
            char passwordChars[] = keyPassword.toCharArray();

            String keyFile = myProps.getProperty("conf.ssl.keyfile");
            keys.load(new FileInputStream(keyFile), passwordChars);

            String keyManagerAlgorithm =
                myProps.getProperty("conf.ssl.keymanageralgorithm", "SunX509");

            KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(keyManagerAlgorithm);
            keyManagerFactory.init(keys, passwordChars);
            KeyManager keyManagers[] = keyManagerFactory.getKeyManagers();

            mySSLContext = SSLContext.getInstance("TLS");
            mySSLContext.init(keyManagers, null, null);

        /* A wide variety of different kinds of problems can happen here, all
           of which entail some form of system misconfiguration and are
           unrecoverable.  The only useful thing to do is die with an
           informative message and let higher powers try again later after
           they've fixed it. */
        } catch (GeneralSecurityException e) {
            Trace.startup.fatalError("problem initializing SSL", e);
        } catch (FileNotFoundException e) {
            Trace.startup.fatalError("SSL key file not found", e);
        } catch (IOException e) {
            Trace.startup.fatalError("problem reading SSL key file", e);
        }
    }

    /**
     * Obtain the SSL context for SSL connections, if there is one.
     *
     * @return the SSL context, if supporting SSL, else null.
     */
    SSLContext sslContext() {
        return mySSLContext;
    }
}
