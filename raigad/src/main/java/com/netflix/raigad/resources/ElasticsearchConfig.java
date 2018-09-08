/**
 * Copyright 2017 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.raigad.resources;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.netflix.raigad.configuration.IConfigSource;
import com.netflix.raigad.configuration.IConfiguration;
import com.netflix.raigad.identity.RaigadInstance;
import com.netflix.raigad.startup.RaigadServer;
import com.netflix.raigad.utils.ElasticsearchUtils;
import com.netflix.raigad.utils.TribeUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * This servlet will provide the configuration API service as and when Elasticsearch requests for it.
 */
@Path("/v1/esconfig")
public class ElasticsearchConfig {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchConfig.class);

    private final RaigadServer raigadServer;
    private final TribeUtils tribeUtils;
    private final IConfigSource configSrc;

    @Inject
    public ElasticsearchConfig(RaigadServer raigadServer, TribeUtils tribeUtils, @Named("custom") IConfigSource configSrc, IConfiguration config) {
        this.raigadServer = raigadServer;
        this.tribeUtils = tribeUtils;
        this.configSrc = configSrc;
        this.configSrc.initialize(config);
    }

    @GET
    @Path("/get_nodes")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getNodes() {
        try {
            logger.info("Getting cluster nodes");
            final List<RaigadInstance> instances = raigadServer.getInstanceManager().getAllInstances();

            if (instances == null) {
                logger.error("Error getting cluster nodes");
                return Response.serverError().build();
            }

            logger.info("Got {} instances", instances.size());
            JSONObject raigadJson = ElasticsearchUtils.transformRaigadInstanceToJson(instances);
            return Response.ok(raigadJson.toString()).build();
        } catch (Exception e) {
            logger.error("Error getting nodes (getNodes)", e);
            return Response.serverError().build();
        }
    }

    @GET
    @Path("/get_tribe_nodes/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getTribeNodes(@PathParam("id") String id) {
        try {
            logger.info("Getting nodes for the source tribe cluster [{}]", id);

            // Find source cluster name from the tribe ID by reading YAML file
            String sourceTribeClusterName = tribeUtils.getTribeClusterNameFromId(id);

            if (StringUtils.isEmpty(sourceTribeClusterName)) {
                logger.error("Source tribe cluster name is null or empty, check configuration");
                return Response.serverError().build();
            }

            logger.info("Found source tribe cluster {} with ID [{}]", sourceTribeClusterName, id);

            final List<RaigadInstance> instances =
                    raigadServer.getInstanceManager().getAllInstancesPerCluster(sourceTribeClusterName);

            if (instances == null) {
                logger.error("Error getting source tribe cluster nodes for {}", sourceTribeClusterName);
                return Response.serverError().build();
            }

            logger.info("Got {} instances for {}", instances.size(), sourceTribeClusterName);
            JSONObject raigadJson = ElasticsearchUtils.transformRaigadInstanceToJson(instances);
            return Response.ok(raigadJson.toString()).build();
        } catch (Exception e) {
            logger.error("Exception getting nodes (getTribeNodes)", e);
            return Response.serverError().build();
        }
    }

    @GET
    @Path("/get_prop/{names}")
    @Produces(MediaType.APPLICATION_JSON)
    /*
    A means to fetch Fast Properties via REST
    @param names - comma separated list of property name
     */
    public Response getProperty(@PathParam("names") String propNames) {
        if (propNames.isEmpty())
            return Response.status(Response.Status.NO_CONTENT).build();

        JsonObject fastPropResults = new JsonObject();

        final String[] pNamesSplit = propNames.split(",");
        final Stream<String> pNamesStream = Arrays.stream(pNamesSplit);
        pNamesStream.forEach(
                (propName) -> {
                    try{
                        String s = this.configSrc.get(propName);
                        fastPropResults.addProperty(propName, s);
                    } catch (Exception e) {
                        Response.ok("Exception fetcing property " + propName + ", msg: " + e.getLocalizedMessage()).build();
                    }
                }
        );


        return Response.ok(fastPropResults.toString()).build();
    }
}