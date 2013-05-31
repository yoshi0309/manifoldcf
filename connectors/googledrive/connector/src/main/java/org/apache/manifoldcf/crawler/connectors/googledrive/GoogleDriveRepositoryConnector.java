/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.manifoldcf.crawler.connectors.googledrive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.manifoldcf.crawler.system.Logging;
import org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.core.interfaces.ConfigParams;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.commons.lang.StringUtils;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.core.interfaces.IHTTPOutput;
import org.apache.manifoldcf.core.interfaces.IPostParameters;
import org.apache.manifoldcf.core.interfaces.IThreadContext;
import org.apache.manifoldcf.core.interfaces.SpecificationNode;
import org.apache.manifoldcf.crawler.interfaces.DocumentSpecification;
import org.apache.manifoldcf.crawler.interfaces.IProcessActivity;
import org.apache.manifoldcf.crawler.interfaces.ISeedingActivity;
import org.apache.log4j.Logger;
import com.google.api.services.drive.model.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map.Entry;

/**
 *
 * @author andrew
 */
public class GoogleDriveRepositoryConnector extends BaseRepositoryConnector {

    protected final static String ACTIVITY_READ = "read document";
    public final static String ACTIVITY_FETCH = "fetch";
    protected static final String RELATIONSHIP_CHILD = "child";
    private static final String JOB_STARTPOINT_NODE_TYPE = "startpoint";
    private static final String GOOGLEDRIVE_SERVER_TAB_PROPERTY = "GoogleDriveRepositoryConnector.Server";
    private static final String GOOGLEDRIVE_QUERY_TAB_PROPERTY = "GoogleDriveRepositoryConnector.GoogleDriveQuery";
    // Template names
    /**
     * Forward to the javascript to check the configuration parameters
     */
    private static final String EDIT_CONFIG_HEADER_FORWARD = "editConfiguration_google_server.js";
    /**
     * Server tab template
     */
    private static final String EDIT_CONFIG_FORWARD_SERVER = "editConfiguration_google_server.html";
    /**
     * Forward to the javascript to check the specification parameters for the
     * job
     */
    private static final String EDIT_SPEC_HEADER_FORWARD = "editSpecification_googledrive.js";
    /**
     * Forward to the template to edit the configuration parameters for the job
     */
    private static final String EDIT_SPEC_FORWARD_GOOGLEDRIVEQUERY = "editSpecification_googledriveQuery.html";
    /**
     * Forward to the HTML template to view the configuration parameters
     */
    private static final String VIEW_CONFIG_FORWARD = "viewConfiguration_googledrive.html";
    /**
     * Forward to the template to view the specification parameters for the job
     */
    private static final String VIEW_SPEC_FORWARD = "viewSpecification_googledrive.html";
    /**
     * Endpoint server name
     */
    protected String server = "googledrive";
    protected GoogleDriveSession session = null;
    protected long lastSessionFetch = -1L;
    protected static final long timeToRelease = 300000L;
    protected String clientid = null;
    protected String clientsecret = null;
    protected String refreshtoken = null;
    protected Map<String, String> parameters = new HashMap<String, String>();

    public GoogleDriveRepositoryConnector() {
        super();
    }

    /**
     * Return the list of activities that this connector supports (i.e. writes
     * into the log).
     *
     * @return the list.
     */
    @Override
    public String[] getActivitiesList() {
        return new String[]{ACTIVITY_FETCH, ACTIVITY_READ};
    }

    /**
     * Get the bin name strings for a document identifier. The bin name
     * describes the queue to which the document will be assigned for throttling
     * purposes. Throttling controls the rate at which items in a given queue
     * are fetched; it does not say anything about the overall fetch rate, which
     * may operate on multiple queues or bins. For example, if you implement a
     * web crawler, a good choice of bin name would be the server name, since
     * that is likely to correspond to a real resource that will need real
     * throttle protection.
     *
     * @param documentIdentifier is the document identifier.
     * @return the set of bin names. If an empty array is returned, it is
     * equivalent to there being no request rate throttling available for this
     * identifier.
     */
    @Override
    public String[] getBinNames(String documentIdentifier) {
        return new String[]{server};
    }

    /**
     * Close the connection. Call this before discarding the connection.
     */
    @Override
    public void disconnect() throws ManifoldCFException {
        if (session != null) {
            DestroySessionThread t = new DestroySessionThread();
            try {
                t.start();
                t.join();
                Throwable thr = t.getException();
                if (thr != null) {
                    if (thr instanceof RemoteException) {
                        throw (RemoteException) thr;
                    } else {
                        throw (Error) thr;
                    }
                }
                session = null;
                lastSessionFetch = -1L;
            } catch (InterruptedException e) {
                t.interrupt();
                throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
                        ManifoldCFException.INTERRUPTED);
            } catch (RemoteException e) {
                Throwable e2 = e.getCause();
                if (e2 instanceof InterruptedException
                        || e2 instanceof InterruptedIOException) {

                    throw new ManifoldCFException(e2.getMessage(), e2,
                            ManifoldCFException.INTERRUPTED);
                }

                session = null;
                lastSessionFetch = -1L;

                // Treat this as a transient problem

                Logging.connectors.warn(
                        "GOOGLEDRIVE: Transient remote exception closing session: "
                        + e.getMessage(), e);
            }

        }

        clientid = null;
        clientsecret = null;
        refreshtoken = null;
    }

    /**
     * This method create a new GOOGLEDRIVE session for a GOOGLEDRIVE
     * repository, if the repositoryId is not provided in the configuration, the
     * connector will retrieve all the repositories exposed for this endpoint
     * the it will start to use the first one.
     *
     * @param configParameters is the set of configuration parameters, which in
     * this case describe the target appliance, basic auth configuration, etc.
     * (This formerly came out of the ini file.)
     */
    @Override
    public void connect(ConfigParams configParams) {
        super.connect(configParams);

        clientid = params.getParameter(GoogleDriveConfig.CLIENT_ID_PARAM);
        clientsecret = params.getParameter(GoogleDriveConfig.CLIENT_SECRET_PARAM);
        refreshtoken = params.getParameter(GoogleDriveConfig.REFRESH_TOKEN_PARAM);
    }

    /**
     * Test the connection. Returns a string describing the connection
     * integrity.
     *
     * @return the connection's status as a displayable string.
     */
    @Override
    public String check() throws ManifoldCFException {
        try {
            checkConnection();
            return super.check();
        } catch (ServiceInterruption e) {
            return "Connection temporarily failed: " + e.getMessage();
        } catch (ManifoldCFException e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    protected class DestroySessionThread extends Thread {

        protected Throwable exception = null;

        public DestroySessionThread() {
            super();
            setDaemon(true);
        }

        public void run() {
            try {
                session = null;
            } catch (Throwable e) {
                this.exception = e;
            }
        }

        public Throwable getException() {
            return exception;
        }
    }

    protected class CheckConnectionThread extends Thread {

        protected Throwable exception = null;

        public CheckConnectionThread() {
            super();
            setDaemon(true);
        }

        public void run() {
            try {
                session.getRepositoryInfo();
            } catch (Throwable e) {
                Logging.connectors.warn("GOOGLEDRIVE: Error checking repository: " + e.getMessage(), e);
                this.exception = e;
            }
        }

        public Throwable getException() {
            return exception;
        }
    }

    protected void checkConnection() throws ManifoldCFException,
            ServiceInterruption {
        while (true) {
            boolean noSession = (session == null);
            getSession();
            long currentTime;
            CheckConnectionThread t = new CheckConnectionThread();
            try {
                t.start();
                t.join();
                Throwable thr = t.getException();
                if (thr != null) {
                    if (thr instanceof RemoteException) {
                        throw (RemoteException) thr;
                    } else {
                        throw (Error) thr;
                    }
                }
                return;
            } catch (InterruptedException e) {
                t.interrupt();
                throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
                        ManifoldCFException.INTERRUPTED);
            } catch (RemoteException e) {
                Throwable e2 = e.getCause();
                if (e2 instanceof InterruptedException
                        || e2 instanceof InterruptedIOException) {
                    throw new ManifoldCFException(e2.getMessage(), e2,
                            ManifoldCFException.INTERRUPTED);
                }
                if (noSession) {
                    currentTime = System.currentTimeMillis();
                    throw new ServiceInterruption(
                            "Transient error connecting to filenet service: "
                            + e.getMessage(), currentTime + 60000L);
                }
                session = null;
                lastSessionFetch = -1L;
                continue;
            }
        }
    }

    /**
     * Set up a session
     */
    protected void getSession() throws ManifoldCFException, ServiceInterruption {
        if (session == null) {
            // Check for parameter validity

            if (StringUtils.isEmpty(clientid)) {
                throw new ManifoldCFException("Parameter " + GoogleDriveConfig.CLIENT_ID_PARAM
                        + " required but not set");
            }

            if (Logging.connectors.isDebugEnabled()) {
                Logging.connectors.debug("GOOGLEDRIVE: Clientid = '" + clientid + "'");
            }

            if (StringUtils.isEmpty(clientsecret)) {
                throw new ManifoldCFException("Parameter " + GoogleDriveConfig.CLIENT_SECRET_PARAM
                        + " required but not set");
            }

            if (Logging.connectors.isDebugEnabled()) {
                Logging.connectors.debug("GOOGLEDRIVE: Clientsecret = '" + clientsecret + "'");
            }

            if (StringUtils.isEmpty(refreshtoken)) {
                throw new ManifoldCFException("Parameter " + GoogleDriveConfig.REFRESH_TOKEN_PARAM
                        + " required but not set");
            }

            if (Logging.connectors.isDebugEnabled()) {
                Logging.connectors.debug("GOOGLEDRIVE: refreshtoken = '" + refreshtoken + "'");
            }



            long currentTime;
            GetSessionThread t = new GetSessionThread();
            try {
                t.start();
                t.join();
                Throwable thr = t.getException();
                if (thr != null) {
                    if (thr instanceof java.net.MalformedURLException) {
                        throw (java.net.MalformedURLException) thr;
                    } else if (thr instanceof NotBoundException) {
                        throw (NotBoundException) thr;
                    } else if (thr instanceof RemoteException) {
                        throw (RemoteException) thr;
                    } else {
                        throw (Error) thr;
                    }

                }
            } catch (InterruptedException e) {
                t.interrupt();
                throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
                        ManifoldCFException.INTERRUPTED);
            } catch (java.net.MalformedURLException e) {
                throw new ManifoldCFException(e.getMessage(), e);
            } catch (NotBoundException e) {
                // Transient problem: Server not available at the moment.
                Logging.connectors.warn(
                        "GOOGLEDRIVE: Server not up at the moment: " + e.getMessage(), e);
                currentTime = System.currentTimeMillis();
                throw new ServiceInterruption(e.getMessage(), currentTime + 60000L);
            } catch (RemoteException e) {
                Throwable e2 = e.getCause();
                if (e2 instanceof InterruptedException
                        || e2 instanceof InterruptedIOException) {
                    throw new ManifoldCFException(e2.getMessage(), e2,
                            ManifoldCFException.INTERRUPTED);
                }
                // Treat this as a transient problem
                Logging.connectors.warn(
                        "GOOGLEDRIVE: Transient remote exception creating session: "
                        + e.getMessage(), e);
                currentTime = System.currentTimeMillis();
                throw new ServiceInterruption(e.getMessage(), currentTime + 60000L);
            }

        }
        lastSessionFetch = System.currentTimeMillis();
    }

    protected class GetSessionThread extends Thread {

        protected Throwable exception = null;

        public GetSessionThread() {
            super();
            setDaemon(true);
        }

        public void run() {
            try {
                // Create a session
                parameters.clear();

                // user credentials
                parameters.put(GoogleDriveConfig.CLIENT_ID_PARAM, clientid);
                parameters.put(GoogleDriveConfig.CLIENT_SECRET_PARAM, clientsecret);
                parameters.put(GoogleDriveConfig.REFRESH_TOKEN_PARAM, refreshtoken);
                try {
                    session = new GoogleDriveSession(parameters);
                } catch (Exception e) {
                    Logging.connectors.error("GOOGLEDRIVE: Error during the creation of the new session. Please check the endpoint parameters: " + e.getMessage(), e);
                    this.exception = e;
                }
            } catch (Throwable e) {
                this.exception = e;
            }
        }

        public Throwable getException() {
            return exception;
        }
    }

    @Override
    public void poll() throws ManifoldCFException {
        if (lastSessionFetch == -1L) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime >= lastSessionFetch + timeToRelease) {
            DestroySessionThread t = new DestroySessionThread();
            try {
                t.start();
                t.join();
                Throwable thr = t.getException();
                if (thr != null) {
                    if (thr instanceof RemoteException) {
                        throw (RemoteException) thr;
                    } else {
                        throw (Error) thr;
                    }
                }
                session = null;
                lastSessionFetch = -1L;
            } catch (InterruptedException e) {
                t.interrupt();
                throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
                        ManifoldCFException.INTERRUPTED);
            } catch (RemoteException e) {
                Throwable e2 = e.getCause();
                if (e2 instanceof InterruptedException
                        || e2 instanceof InterruptedIOException) {
                    throw new ManifoldCFException(e2.getMessage(), e2,
                            ManifoldCFException.INTERRUPTED);
                }
                session = null;
                lastSessionFetch = -1L;
                // Treat this as a transient problem
                Logging.connectors.warn(
                        "GOOGLEDRIVE: Transient remote exception closing session: "
                        + e.getMessage(), e);
            }

        }
    }

    /**
     * Get the maximum number of documents to amalgamate together into one
     * batch, for this connector.
     *
     * @return the maximum number. 0 indicates "unlimited".
     */
    @Override
    public int getMaxDocumentRequest() {
        return 1;
    }

    /**
     * Return the list of relationship types that this connector recognizes.
     *
     * @return the list.
     */
    @Override
    public String[] getRelationshipTypes() {
        return new String[]{RELATIONSHIP_CHILD};
    }

    /**
     * Fill in a Server tab configuration parameter map for calling a Velocity
     * template.
     *
     * @param newMap is the map to fill in
     * @param parameters is the current set of configuration parameters
     */
    private static void fillInServerConfigurationMap(Map<String, String> newMap, ConfigParams parameters) {
        String clientid = parameters.getParameter(GoogleDriveConfig.CLIENT_ID_PARAM);
        String clientsecret = parameters.getParameter(GoogleDriveConfig.CLIENT_SECRET_PARAM);
        String refreshtoken = parameters.getParameter(GoogleDriveConfig.REFRESH_TOKEN_PARAM);

        if (clientid == null) {
            clientid = StringUtils.EMPTY;
        }
        if (clientsecret == null) {
            clientsecret = StringUtils.EMPTY;
        }


        if (refreshtoken == null) {
            refreshtoken = StringUtils.EMPTY;
        }

        newMap.put(GoogleDriveConfig.CLIENT_ID_PARAM, clientid);
        newMap.put(GoogleDriveConfig.CLIENT_SECRET_PARAM, clientsecret);
        newMap.put(GoogleDriveConfig.REFRESH_TOKEN_PARAM, refreshtoken);
    }

    /**
     * View configuration. This method is called in the body section of the
     * connector's view configuration page. Its purpose is to present the
     * connection information to the user. The coder can presume that the HTML
     * that is output from this configuration will be within appropriate <html>
     * and <body> tags.
     *
     * @param threadContext is the local thread context.
     * @param out is the output to which any HTML should be sent.
     * @param parameters are the configuration parameters, as they currently
     * exist, for this connection being configured.
     */
    @Override
    public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
            Locale locale, ConfigParams parameters) throws ManifoldCFException, IOException {
        Map<String, String> paramMap = new HashMap<String, String>();

        // Fill in map from each tab
        fillInServerConfigurationMap(paramMap, parameters);

        outputResource(VIEW_CONFIG_FORWARD, out, locale, paramMap);
    }

    /**
     * Read the content of a resource, replace the variable ${PARAMNAME} with
     * the value and copy it to the out.
     *
     * @param resName
     * @param out
     * @throws ManifoldCFException
     */
    private static void outputResource(String resName, IHTTPOutput out,
            Locale locale, Map<String, String> paramMap) throws ManifoldCFException {
        Messages.outputResourceWithVelocity(out, locale, resName, paramMap, true);
    }

    /**
     *
     * Output the configuration header section. This method is called in the
     * head section of the connector's configuration page. Its purpose is to add
     * the required tabs to the list, and to output any javascript methods that
     * might be needed by the configuration editing HTML.
     *
     * @param threadContext is the local thread context.
     * @param out is the output to which any HTML should be sent.
     * @param parameters are the configuration parameters, as they currently
     * exist, for this connection being configured.
     * @param tabsArray is an array of tab names. Add to this array any tab
     * names that are specific to the connector.
     */
    @Override
    public void outputConfigurationHeader(IThreadContext threadContext,
            IHTTPOutput out, Locale locale, ConfigParams parameters, List<String> tabsArray)
            throws ManifoldCFException, IOException {
        // Add the Server tab
        tabsArray.add(Messages.getString(locale, GOOGLEDRIVE_SERVER_TAB_PROPERTY));
        // Map the parameters
        Map<String, String> paramMap = new HashMap<String, String>();

        // Fill in the parameters from each tab
        fillInServerConfigurationMap(paramMap, parameters);

        // Output the Javascript - only one Velocity template for all tabs
        outputResource(EDIT_CONFIG_HEADER_FORWARD, out, locale, paramMap);
    }

    @Override
    public void outputConfigurationBody(IThreadContext threadContext,
            IHTTPOutput out, Locale locale, ConfigParams parameters, String tabName)
            throws ManifoldCFException, IOException {

        // Call the Velocity templates for each tab

        // Server tab
        Map<String, String> paramMap = new HashMap<String, String>();
        // Set the tab name
        paramMap.put("TabName", tabName);
        // Fill in the parameters
        fillInServerConfigurationMap(paramMap, parameters);
        outputResource(EDIT_CONFIG_FORWARD_SERVER, out, locale, paramMap);

    }

    /**
     * Process a configuration post. This method is called at the start of the
     * connector's configuration page, whenever there is a possibility that form
     * data for a connection has been posted. Its purpose is to gather form
     * information and modify the configuration parameters accordingly. The name
     * of the posted form is "editconnection".
     *
     * @param threadContext is the local thread context.
     * @param variableContext is the set of variables available from the post,
     * including binary file post information.
     * @param parameters are the configuration parameters, as they currently
     * exist, for this connection being configured.
     * @return null if all is well, or a string error message if there is an
     * error that should prevent saving of the connection (and cause a
     * redirection to an error page).
     *
     */
    @Override
    public String processConfigurationPost(IThreadContext threadContext,
            IPostParameters variableContext, ConfigParams parameters)
            throws ManifoldCFException {

        String clientid = variableContext.getParameter(GoogleDriveConfig.CLIENT_ID_PARAM);
        if (clientid != null) {
            parameters.setParameter(GoogleDriveConfig.CLIENT_ID_PARAM, clientid);
        }

        String clientsecret = variableContext.getParameter(GoogleDriveConfig.CLIENT_SECRET_PARAM);
        if (clientsecret != null) {
            parameters.setParameter(GoogleDriveConfig.CLIENT_SECRET_PARAM, clientsecret);
        }

        String refreshtoken = variableContext.getParameter(GoogleDriveConfig.REFRESH_TOKEN_PARAM);
        if (refreshtoken != null) {
            parameters.setParameter(GoogleDriveConfig.REFRESH_TOKEN_PARAM, refreshtoken);
        }

        return null;
    }

    /**
     * Fill in specification Velocity parameter map for GOOGLEDRIVEQuery tab.
     */
    private static void fillInGOOGLEDRIVEQuerySpecificationMap(Map<String, String> newMap, DocumentSpecification ds) {
        int i = 0;
        String GoogleDriveQuery = GoogleDriveConfig.GOOGLEDRIVE_QUERY_DEFAULT;
        while (i < ds.getChildCount()) {
            SpecificationNode sn = ds.getChild(i);
            if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
                GoogleDriveQuery = sn.getAttributeValue(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM);
            }
            i++;
        }
        newMap.put(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM, GoogleDriveQuery);
    }

    /**
     * View specification. This method is called in the body section of a job's
     * view page. Its purpose is to present the document specification
     * information to the user. The coder can presume that the HTML that is
     * output from this configuration will be within appropriate <html> and
     * <body> tags.
     *
     * @param out is the output to which any HTML should be sent.
     * @param ds is the current document specification for this job.
     */
    @Override
    public void viewSpecification(IHTTPOutput out, Locale locale, DocumentSpecification ds)
            throws ManifoldCFException, IOException {

        Map<String, String> paramMap = new HashMap<String, String>();

        // Fill in the map with data from all tabs
        fillInGOOGLEDRIVEQuerySpecificationMap(paramMap, ds);

        outputResource(VIEW_SPEC_FORWARD, out, locale, paramMap);
    }

    /**
     * Process a specification post. This method is called at the start of job's
     * edit or view page, whenever there is a possibility that form data for a
     * connection has been posted. Its purpose is to gather form information and
     * modify the document specification accordingly. The name of the posted
     * form is "editjob".
     *
     * @param variableContext contains the post data, including binary
     * file-upload information.
     * @param ds is the current document specification for this job.
     * @return null if all is well, or a string error message if there is an
     * error that should prevent saving of the job (and cause a redirection to
     * an error page).
     */
    @Override
    public String processSpecificationPost(IPostParameters variableContext,
            DocumentSpecification ds) throws ManifoldCFException {
        String cmisQuery = variableContext.getParameter(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM);
        if (cmisQuery != null) {
            int i = 0;
            while (i < ds.getChildCount()) {
                SpecificationNode oldNode = ds.getChild(i);
                if (oldNode.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
                    ds.removeChild(i);
                    break;
                }
                i++;
            }
            SpecificationNode node = new SpecificationNode(JOB_STARTPOINT_NODE_TYPE);
            node.setAttribute(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM, cmisQuery);
            variableContext.setParameter(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM, cmisQuery);
            ds.addChild(ds.getChildCount(), node);
        }
        return null;
    }

    /**
     * Output the specification body section. This method is called in the body
     * section of a job page which has selected a repository connection of the
     * current type. Its purpose is to present the required form elements for
     * editing. The coder can presume that the HTML that is output from this
     * configuration will be within appropriate <html>, <body>, and <form> tags.
     * The name of the form is "editjob".
     *
     * @param out is the output to which any HTML should be sent.
     * @param ds is the current document specification for this job.
     * @param tabName is the current tab name.
     */
    @Override
    public void outputSpecificationBody(IHTTPOutput out,
            Locale locale, DocumentSpecification ds, String tabName) throws ManifoldCFException,
            IOException {

        // Output GOOGLEDRIVEQuery tab
        Map<String, String> paramMap = new HashMap<String, String>();
        paramMap.put("TabName", tabName);
        fillInGOOGLEDRIVEQuerySpecificationMap(paramMap, ds);
        outputResource(EDIT_SPEC_FORWARD_GOOGLEDRIVEQUERY, out, locale, paramMap);
    }

    /**
     * Output the specification header section. This method is called in the
     * head section of a job page which has selected a repository connection of
     * the current type. Its purpose is to add the required tabs to the list,
     * and to output any javascript methods that might be needed by the job
     * editing HTML.
     *
     * @param out is the output to which any HTML should be sent.
     * @param ds is the current document specification for this job.
     * @param tabsArray is an array of tab names. Add to this array any tab
     * names that are specific to the connector.
     */
    @Override
    public void outputSpecificationHeader(IHTTPOutput out,
            Locale locale, DocumentSpecification ds, List<String> tabsArray)
            throws ManifoldCFException, IOException {
        tabsArray.add(Messages.getString(locale, GOOGLEDRIVE_QUERY_TAB_PROPERTY));

        Map<String, String> paramMap = new HashMap<String, String>();

        // Fill in the specification header map, using data from all tabs.
        fillInGOOGLEDRIVEQuerySpecificationMap(paramMap, ds);

        outputResource(EDIT_SPEC_HEADER_FORWARD, out, locale, paramMap);
    }

    /**
     * Queue "seed" documents. Seed documents are the starting places for
     * crawling activity. Documents are seeded when this method calls
     * appropriate methods in the passed in ISeedingActivity object.
     *
     * This method can choose to find repository changes that happen only during
     * the specified time interval. The seeds recorded by this method will be
     * viewed by the framework based on what the getConnectorModel() method
     * returns.
     *
     * It is not a big problem if the connector chooses to create more seeds
     * than are strictly necessary; it is merely a question of overall work
     * required.
     *
     * The times passed to this method may be interpreted for greatest
     * efficiency. The time ranges any given job uses with this connector will
     * not overlap, but will proceed starting at 0 and going to the "current
     * time", each time the job is run. For continuous crawling jobs, this
     * method will be called once, when the job starts, and at various periodic
     * intervals as the job executes.
     *
     * When a job's specification is changed, the framework automatically resets
     * the seeding start time to 0. The seeding start time may also be set to 0
     * on each job run, depending on the connector model returned by
     * getConnectorModel().
     *
     * Note that it is always ok to send MORE documents rather than less to this
     * method.
     *
     * @param activities is the interface this method should use to perform
     * whatever framework actions are desired.
     * @param spec is a document specification (that comes from the job).
     * @param startTime is the beginning of the time range to consider,
     * inclusive.
     * @param endTime is the end of the time range to consider, exclusive.
     * @param jobMode is an integer describing how the job is being run, whether
     * continuous or once-only.
     */
    @Override
    public void addSeedDocuments(ISeedingActivity activities,
            DocumentSpecification spec, long startTime, long endTime, int jobMode)
            throws ManifoldCFException, ServiceInterruption {

        try {
            getSession();
            String googleDriveQuery = GoogleDriveConfig.GOOGLEDRIVE_QUERY_DEFAULT;
            int i = 0;
            while (i < spec.getChildCount()) {
                SpecificationNode sn = spec.getChild(i);
                if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
                    googleDriveQuery = sn.getAttributeValue(GoogleDriveConfig.GOOGLEDRIVE_QUERY_PARAM);
                    break;
                }
                i++;
            }

            //TODO handle different start directories, and save/retreive the cursor from somewhere..
            HashSet<String> seeds = session.getSeeds(googleDriveQuery);
            for (String seed : seeds) {
                activities.addSeedDocument(seed);
            }
        } catch (Exception ex) {
            Logging.connectors.error("GOOGLEDRIVE: Error adding seed documents: " + ex.getMessage(), ex);
        }
    }

    /**
     * Process a set of documents. This is the method that should cause each
     * document to be fetched, processed, and the results either added to the
     * queue of documents for the current job, and/or entered into the
     * incremental ingestion manager. The document specification allows this
     * class to filter what is done based on the job.
     *
     * @param documentIdentifiers is the set of document identifiers to process.
     * @param versions is the corresponding document versions to process, as
     * returned by getDocumentVersions() above. The implementation may choose to
     * ignore this parameter and always process the current version.
     * @param activities is the interface this method should use to queue up new
     * document references and ingest documents.
     * @param spec is the document specification.
     * @param scanOnly is an array corresponding to the document identifiers. It
     * is set to true to indicate when the processing should only find other
     * references, and should not actually call the ingestion methods.
     * @param jobMode is an integer describing how the job is being run, whether
     * continuous or once-only.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void processDocuments(String[] documentIdentifiers, String[] versions,
            IProcessActivity activities, DocumentSpecification spec,
            boolean[] scanOnly) throws ManifoldCFException, ServiceInterruption {
        getSession();
        Logging.connectors.debug("GOOGLEDRIVE: Inside processDocuments");
        int i = 0;

        while (i < documentIdentifiers.length) {
            try {
                long startTime = System.currentTimeMillis();
                String nodeId = documentIdentifiers[i];
                if (Logging.connectors.isDebugEnabled()) {
                    Logging.connectors.debug("GOOGLEDRIVE: Processing document identifier '"
                            + nodeId + "'");
                }

                File googleFile = session.getObject(nodeId);

                String errorCode = "OK";
                String errorDesc = StringUtils.EMPTY;
                if (googleFile.containsKey("explicitlyTrashed") && googleFile.getExplicitlyTrashed()) { //its deleted, move on
                    continue;
                }


                if (Logging.connectors.isDebugEnabled()) {
                    Logging.connectors.debug("GOOGLEDRIVE: have this file:\t" + googleFile.getTitle());
                }

                if ("application/vnd.google-apps.folder".equals(googleFile.getMimeType())) { //if directory add its children

                    if (Logging.connectors.isDebugEnabled()) {
                        Logging.connectors.debug("GOOGLEDRIVE: its a directory");
                    }

                    // adding all the children + subdirs for a folder

                    List<String> children = session.getChildren(nodeId);
                    for (String child : children) {
                        activities.addDocumentReference(child, nodeId, RELATIONSHIP_CHILD);
                    }

                } else { // its a file
                    InputStream is = null;
                    long fileLength = 0;
                    if (Logging.connectors.isDebugEnabled()) {
                        Logging.connectors.debug("GOOGLEDRIVE: its a file");
                    }
                    //otherwise process

                    try {
                        RepositoryDocument rd = new RepositoryDocument();
                        ByteArrayOutputStream os;
                        String documentURI = session.getUrl(googleFile, "application/pdf");
                        os = session.getGoogleDriveOutputStream(documentURI); // convert it to plain text..many other supported types
                        byte[] contents = os.toByteArray();

                        is = new ByteArrayInputStream(contents);
                        fileLength = contents.length;
                        //binary
                        rd.setBinary(is, fileLength);

                        for (Entry<String, Object> entry : googleFile.entrySet()) {
                            rd.addField(entry.getKey(), entry.getValue().toString());
                        }

                        //ingestion

                        String version = googleFile.getModifiedDate().toStringRfc3339();
                        if (StringUtils.isEmpty(version)) {
                            version = StringUtils.EMPTY;
                        }

                        //documentURI

                        activities.ingestDocument(nodeId, version, documentURI, rd);
                    } catch (Exception ex) {
                        Logging.connectors.error("GOOGLEDRIVE: Error processing documents: " + ex.getMessage(), ex);
                    } finally {
                        try {
                            if (is != null) {
                                is.close();
                            }
                        } catch (InterruptedIOException e) {
                            errorCode = "Interrupted error";
                            errorDesc = e.getMessage();
                            throw new ManifoldCFException(e.getMessage(), e,
                                    ManifoldCFException.INTERRUPTED);
                        } catch (IOException e) {
                            errorCode = "IO ERROR";
                            errorDesc = e.getMessage();
                            Logging.connectors.warn(
                                    "GOOGLEDRIVE: IOException closing file input stream: "
                                    + e.getMessage(), e);
                        }

                        activities.recordActivity(new Long(startTime), ACTIVITY_READ,
                                fileLength, nodeId, errorCode, errorDesc, null);
                    }


                }
            } catch (Exception ex) {
                Logging.connectors.error("GOOGLEDRIVE: Error processing documents 2: " + ex.getMessage(), ex);
            }
            i++;
        }
    }

    /**
     * The short version of getDocumentVersions. Get document versions given an
     * array of document identifiers. This method is called for EVERY document
     * that is considered. It is therefore important to perform as little work
     * as possible here.
     *
     * @param documentIdentifiers is the array of local document identifiers, as
     * understood by this connector.
     * @param spec is the current document specification for the current job. If
     * there is a dependency on this specification, then the version string
     * should include the pertinent data, so that reingestion will occur when
     * the specification changes. This is primarily useful for metadata.
     * @return the corresponding version strings, with null in the places where
     * the document no longer exists. Empty version strings indicate that there
     * is no versioning ability for the corresponding document, and the document
     * will always be processed.
     */
    @Override
    public String[] getDocumentVersions(String[] documentIdentifiers,
            DocumentSpecification spec) throws ManifoldCFException,
            ServiceInterruption {
        getSession();
        String[] rval = new String[documentIdentifiers.length];
        int i = 0;
        while (i < rval.length) {
            try {
                File googleFile = session.getObject(documentIdentifiers[i]);
                if (!isDir(googleFile)) {
                    //we have to check if this CMIS repository support versioning
                    // or if the versioning is disabled for this content
                    String rev = googleFile.getModifiedDate().toStringRfc3339();
                    if (StringUtils.isNotEmpty(rev)) {
                        rval[i] = rev;
                    } else {
                        //a CMIS document that doesn't contain versioning information will always be processed
                        rval[i] = StringUtils.EMPTY;
                    }
                } else {
                    //a CMIS folder will always be processed
                    rval[i] = StringUtils.EMPTY;
                }
            } catch (Exception ex) {
                Logging.connectors.error("GOOGLEDRIVE: Error getting document versions: " + ex.getMessage(), ex);
            }
            i++;
        }
        return rval;
    }

    private boolean isDir(File f) {
        return f.getMimeType().compareToIgnoreCase("application/vnd.google-apps.folder") == 0;
    }
}