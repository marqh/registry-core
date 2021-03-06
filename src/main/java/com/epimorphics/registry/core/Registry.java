/******************************************************************
 * File:        Registry.java
 * Created by:  Dave Reynolds
 * Created on:  26 Jan 2013
 *
 * (c) Copyright 2013, Epimorphics Limited
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *****************************************************************/

package com.epimorphics.registry.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.UnavailableSecurityManagerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.rdfutil.ModelWrapper;
import com.epimorphics.registry.core.Command.Operation;
import com.epimorphics.registry.core.ForwardingRecord.Type;
import com.epimorphics.registry.message.LocalMessagingService;
import com.epimorphics.registry.message.MessagingService;
import com.epimorphics.registry.security.UserInfo;
import com.epimorphics.registry.security.UserStore;
import com.epimorphics.registry.store.CachingStore;
import com.epimorphics.registry.store.StoreAPI;
import com.epimorphics.registry.util.Prefixes;
import com.epimorphics.registry.vocab.RegistryVocab;
import com.epimorphics.server.core.Service;
import com.epimorphics.server.core.ServiceBase;
import com.epimorphics.server.core.ServiceConfig;
import com.epimorphics.server.core.Shutdown;
import com.epimorphics.server.templates.VelocityRender;
import com.epimorphics.server.webapi.WebApiException;
import com.epimorphics.server.webapi.facets.FacetService;
import com.epimorphics.util.EpiException;
import com.epimorphics.util.FileUtil;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.util.FileUtils;
import com.hp.hpl.jena.vocabulary.RDF;
import com.sun.jersey.api.uri.UriComponent;

/**
 * This the primary configuration point for the Registry.
 * Configuration bindings available are:
 * <ul>
 *   <li>baseURI - the effective base URI for the registry</li>
 *   <li>bootSpec - location of a bootstrap file defining the root register, and system registers</li>
 *   <li>store - named of a configuration service that provides the StoreAPI implementation in which the registry information is stored</li>
 *   <li>cacheSize - size of register cache to use, if not set then no caching is done, typical value 100</li>
 *   <li>pageSize - size to use for LDP pages, default 50 </li>
 * <ul>
 * @author <a href="mailto:dave@epimorphics.com">Dave Reynolds</a>
 */
public class Registry extends ServiceBase implements Service, Shutdown {
    static final Logger log = LoggerFactory.getLogger( Registry.class );

    public static final String VELOCITY_SERVICE = "velocity";

    public static final String BASE_URI_PARAM = "baseURI";
    public static final String BOOT_FILE_PARAM = "bootSpec";
    public static final String SYSTEM_BOOTSTRAP_DIR_PARAM = "systemBoot";
    public static final String LOG_DIR_PARAM = "log";
    public static final String FORWARDER_PARAM = "forwarder";
    public static final String USERSTORE_PARAM = "userStore";
    public static final String STORE_PARAM = "store";
    public static final String CACHE_SIZE_PARAM = "cacheSize";
    public static final String PAGE_SIZE_PARAM = "pageSize";
    public static final String MESSAGE_SERVICE_PARAM = "messageService";
    public static final String FACET_SERVICE_PARAM = "facetService";

    public static final boolean TEXT_INDEX_INCLUDES_HISTORY = true;

    public static final int DEFAULT_PAGE_SIZE = 50;

    protected StoreAPI store;
    protected String baseURI;
    protected int pageSize;
    protected ForwardingService forwarder;
    protected String logDir;
    protected UserStore userStore;
    protected MessagingService messageService;
    protected FacetService facetService;


    @Override
    public void init(Map<String, String> config, ServletContext context) {
        this.config = config;
        baseURI = getRequiredParam(BASE_URI_PARAM);
        if (baseURI.endsWith("/")) {
            baseURI = baseURI.substring(0, baseURI.length() -1 );
        }
        if (config.containsKey(PAGE_SIZE_PARAM)) {
            pageSize = Integer.parseInt(config.get(PAGE_SIZE_PARAM));
        } else {
            pageSize = DEFAULT_PAGE_SIZE;
        }
    }

    @Override
    public void postInit() {
        // Locate the configured store and optionally wrap it in a cache
        try {
            store = getNamedService(getRequiredParam(STORE_PARAM), StoreAPI.class);
            String cacheSizeStr = config.get(CACHE_SIZE_PARAM);
            if (cacheSizeStr != null) {
                int cacheSize = Integer.parseInt(cacheSizeStr);
                store = new CachingStore(store, cacheSize);
            }
        } catch (Exception e) {
            log.error("Misconfigured StoreAPI implementation", e);
        }

        registry = this;   // Assumes singleton registry

        // Configure the messaging service
        String msName = config.get(MESSAGE_SERVICE_PARAM);
        if (msName != null) {
            messageService = getNamedService(msName, MessagingService.class);
        }
        if (messageService == null) {
            messageService = new LocalMessagingService();
        }

        // Initialize the registry RDF store from the bootstrap registers if needed
        Description root = store.getDescription(getBaseURI() + "/");
        if (root == null) {
            // Blank store, need to install a bootstrap root registers
            for(String bootSrc : getRequiredFileParam(BOOT_FILE_PARAM).split("\\|")) {
                log.info("Loading bootstrap file " + bootSrc);
                store.loadBootstrap( bootSrc );
            }
            String bootdirs = config.get(SYSTEM_BOOTSTRAP_DIR_PARAM);
            if (bootdirs != null) {
                for (String bootdir : bootdirs.split("\\|")) {
                    bootdir = ServiceConfig.get().expandFileLocation( bootdir );
                    loadInitialRegisterTree(bootdir);
                }
            }
            log.info("Installed bootstrap root register");
        }

        // Configure the velocity renderer
        VelocityRender velocity = ServiceConfig.get().getServiceAs(Registry.VELOCITY_SERVICE, VelocityRender.class);
        if (velocity != null) {
            velocity.setPrefixes( Prefixes.get() );
        }

        // Initialize the forwarding service from the stored forwarding records
        String fname = config.get(FORWARDER_PARAM);
        if (fname != null) {
            forwarder = getNamedService(fname, ForwardingService.class);

            for (ForwardingRecord fr : store.listDelegations()) {
                forwarder.register(fr);
            }
            forwarder.updateConfig();
        }

        // Configure the log area
        logDir = config.get(LOG_DIR_PARAM);
        if (logDir != null) {
            FileUtil.ensureDir(logDir);
            logDir = ServiceConfig.get().expandFileLocation( logDir );
        }

        // Configure the authorization store
        String usName = config.get(USERSTORE_PARAM);
        if (usName != null) {
            userStore = getNamedService(usName, UserStore.class);
        }

        // Configure optional facet service
        String fsName = config.get(FACET_SERVICE_PARAM);
        if (fsName != null) {
            facetService = getNamedService(fsName, FacetService.class);
        }
    }

    /**
     * Load a set of initial registers and contents defined by sources files in a
     * directory tree. Each source is assumed to be in ttl format. Each directory
     * represents a register and should contain a metadata.ttl file to define RegisterItem
     * metadata for the register itself. The root register should not have metadata.
     * All other files in the register are plain content files which can either be in
     * simple or complex (with RegisterItems) format. Simple entries are assumed to be stable.
     */
    private void loadInitialRegisterTree(String bootdir) {
        File dir = new File(bootdir);
        if (dir.exists()) {
            try {
                loadRegisterTree(null, dir);
            } catch (Exception e) {
                log.error("Failed to load initialization tree", e);
            }
        }
    }

    private void loadRegisterTree(String parent, File dir) {
        File metadata = new File(dir, METADATA_FILE);
        if (metadata.exists()) {
            if (parent != null) {
                registerFile(parent, metadata);
            }
        }
        String register = parent == null ? "" : ((parent.isEmpty() ? "" : parent + "/") + dir.getName());
        String[] filenames = dir.list();
        if (filenames == null) {
            log.warn("Bootstrap directory " + dir.getPath() + " is empty");
        }
        for (String filename : filenames) {
            if (filename.equals(METADATA_FILE)) continue;
            File file = new File(dir, filename);
            if (file.isDirectory()) {
                loadRegisterTree(register, file);
            } else {
                registerFile(register, file);
            }
        }
    }
    private final static String METADATA_FILE = "metadata.ttl";

    private void registerFile(String register, File file)  {
        String baseURI = getBaseURI() + "/" + (register.isEmpty() ? "" : register + "/");
        try {
            Model model = ModelFactory.createDefaultModel();
            BufferedInputStream in = new BufferedInputStream( new FileInputStream(file) );
            model.read(in, baseURI, FileUtils.langTurtle);
            in.close();

            String parameters = "";
            if ( ! model.contains(null, RDF.type, RegistryVocab.RegisterItem)) {
                // Simple content so force status update to stable
                parameters = "update&status=stable";
            }
            Command command = Registry.get().make( Operation.Register, register, parameters);
            command.setPayload( model );
            Response response = command.authorizedExecute();
            if (response.getStatus() >= 400) {
                throw new EpiException("Bootstrap error: " + response.getEntity());
            }
            log.info("Loaded bootstrap file " + file.getPath());
        } catch (WebApiException e) {
            throw new EpiException("Bootstrap error: " + e.getResponse().getEntity());
        } catch (Exception e) {
            throw new EpiException("Bootstrap error on file " + file.getPath(), e);
        }

    }


    public String getBaseURI() {
        return baseURI;
    }

    public StoreAPI getStore() {
        return store;
    }

    public int getPageSize() {
        return pageSize;
    }

    public ForwardingService getForwarder() {
        return forwarder;
    }

    public String getLogDir() {
        return logDir;
    }

    public UserStore getUserStore() {
        return userStore;
    }

    public MessagingService getMessagingService() {
        return messageService;
    }

    public FacetService getFacetService() {
        return facetService;
    }

    /**
     * Factory for command instances, used for handling API requests from the request processor
     */
    public Command make(Operation operation, String target,  MultivaluedMap<String, String> parameters) {
        Command command = operation.makeCommandInstance();
        command.init(operation, target, parameters, this);
        return command;
    }

    public Command make(Operation operation, String target,  String parameters) {
        return make(operation, target, UriComponent.decodeQuery(parameters, true));
    }

    /**
     * Instantiate and invoke commands, used from the ui
     */
    public Response perform(String operation, String uriTarget, String requestor) {
        Operation op = Operation.valueOf(operation);
        URI uri;
        try {
            uri = new URI(uriTarget);
        } catch (URISyntaxException e) {
            throw new EpiException(e);
        }
        String encPath = uri.getRawPath();
        String target = UriComponent.decode(encPath, UriComponent.Type.PATH);
        String queries = uriTarget;
        int split = queries.indexOf("?");
        if (split != -1) {
            queries = queries.substring(split+1);
        }
        MultivaluedMap<String, String> parameters = UriComponent.decodeQuery(queries, true);
        Command command = make(op, target, parameters);

        if (requestor == null || requestor.isEmpty()) {
            try {
                UserInfo user = (UserInfo) SecurityUtils.getSubject().getPrincipal();
                if (user != null) {
                    requestor = user.toString();
                }
            } catch (UnavailableSecurityManagerException e) {
                // shiro not running, presumably test mode, fall through to default requestor based on IP
            }
        }
        command.setRequestor(requestor);

        ForwardingService fs = getForwarder();
        if (fs != null) {
            MatchResult match = fs.match(target);
            if (match != null) {
                ForwardingRecord fr = match.getRecord();
                if (fr.getType() == Type.DELEGATE) {
                    command.setDelegation(fr);
                }
            }
        }

        try {
            Response response = command.execute();
            if (response.getStatus() == 200 && response.getEntity() instanceof Model) {
                Model m = (Model)response.getEntity();
                m.setNsPrefixes(Prefixes.get());
                return Response.ok( new ModelWrapper( m ) ).build();
            } else {
                return response;
            }
        } catch (WebApplicationException wae) {
                return wae.getResponse();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    static Registry registry;
    public static Registry get() {
        return registry;
    }

    @Override
    public void shutdown() {
        Prefixes.shutdown();
    }
}
