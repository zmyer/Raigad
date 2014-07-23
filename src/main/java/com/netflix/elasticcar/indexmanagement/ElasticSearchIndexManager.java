package com.netflix.elasticcar.indexmanagement;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.elasticcar.configuration.IConfiguration;
import com.netflix.elasticcar.indexmanagement.exception.UnsupportedAutoIndexException;
import com.netflix.elasticcar.objectmapper.DefaultIndexMapper;
import com.netflix.elasticcar.scheduler.CronTimer;
import com.netflix.elasticcar.scheduler.Task;
import com.netflix.elasticcar.scheduler.TaskTimer;
import com.netflix.elasticcar.utils.ESTransportClient;
import com.netflix.elasticcar.utils.ElasticsearchProcessMonitor;
import com.netflix.elasticcar.utils.EsUtils;
import com.netflix.elasticcar.utils.HttpModule;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.status.IndexStatus;
import org.elasticsearch.action.admin.indices.status.IndicesStatusResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Singleton
public class ElasticSearchIndexManager extends Task {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchIndexManager.class);
    public static String JOBNAME = "ElasticSearchIndexManager";
    private final HttpModule httpModule;

    @Inject
    protected ElasticSearchIndexManager(IConfiguration config, HttpModule httpModule) {
        super(config);
        this.httpModule = httpModule;
    }

    @Override
    public void execute() {
        try {
            //Confirm if Current Node is a Master Node
            if (EsUtils.amIMasterNode(config, httpModule))
            {
                // If Elasticsearch is started then only start Snapshot Backup
                if (!ElasticsearchProcessMonitor.isElasticsearchStarted()) {
                    String exceptionMsg = "Elasticsearch is not yet started, hence not Starting Index Management Operation";
                    logger.info(exceptionMsg);
                    return;
                }

                logger.info("Current node is the Master Node.");

                if (!config.isIndexAutoCreationEnabled()) {
                    logger.info("Autocreation of Indices is disabled, hence moving on");
                    return;
                }
                logger.info("Starting Index Maintenance ...");

                List<IndexMetadata> infoList;
                try {
                    infoList = buildInfo(config.getIndexMetadata());
                } catch (Exception e) {
                    //TODO Add Servo Monitoring so that it can be verified from dashboard
                    logger.error("Caught an exception while Building IndexMetadata information from Configuration Property");
                    return;
                }

                TransportClient esTransportClient = ESTransportClient.instance(config).getTransportClient();
                for (IndexMetadata indexMetadata : infoList) {
                    try {
                        if (esTransportClient != null) {
                            checkIndexRetention(indexMetadata,esTransportClient);
                            if (indexMetadata.isPreCreate()) {
                                preCreateIndex(indexMetadata,esTransportClient);
                            }
                        }
                    } catch (Exception e) {
                        //TODO Add Servo Monitoring so that it can be verified from dashboard
                        logger.error("Caught an exception while Building IndexMetadata information from Configuration Property");
                        return;
                    }
                }
            }
            else
            {
                //TODO:Update config property
                if (config.isDebugEnabled())
                    logger.debug("Current node is not a Master Node yet, hence sleeping for " + config.getAutoCreateIndexPeriodicScheduledHour() + " Seconds");
            }
        } catch (Exception e)
        {
            logger.warn("Exception thrown while doing Index Maintenance", e);
        }
    }

    @Override
    public String getName() {
        return JOBNAME;
    }

    public static TaskTimer getTimer(IConfiguration config)
    {
//        //Remove after testing
//        return new SimpleTimer(JOBNAME, 30L * 1000);

        int hour = config.getAutoCreateIndexPeriodicScheduledHour();
        return new CronTimer(hour, 1, 0,JOBNAME);
    }

    /**
     * Convert the JSON String of parameters to IndexMetadata objects
     * @param infoStr : JSON String with Parameters
     * @return list of IndexMetadata objects
     * @throws IOException
     */
    public static List<IndexMetadata> buildInfo(String infoStr) throws IOException {
        ObjectMapper jsonMapper = new DefaultIndexMapper();
        TypeReference<List<IndexMetadata>> typeRef = new TypeReference<List<IndexMetadata>>() {};
        return jsonMapper.readValue(infoStr, typeRef);
    }

    /**
     * Courtesy Jae Bae
     */
    public void checkIndexRetention(IndexMetadata indexMetadata,TransportClient esTransportClient) throws UnsupportedAutoIndexException {

        //Calculate the Past Retention date
        int pastRetentionCutoffDateDate = IndexUtils.getPastRetentionCutoffDate(indexMetadata);
        if(config.isDebugEnabled())
            logger.debug("Past Date = " + pastRetentionCutoffDateDate);
        //Find all the indices
        IndicesStatusResponse getIndicesResponse = esTransportClient.admin().indices().prepareStatus().execute().actionGet(config.getAutoCreateIndexTimeout());
        Map<String, IndexStatus> indexStatusMap = getIndicesResponse.getIndices();
        if (!indexStatusMap.isEmpty()) {
            for (String indexName : indexStatusMap.keySet()) {
                if(config.isDebugEnabled())
                    logger.debug("Index Name = <" + indexName + ">");
                if (indexMetadata.getIndexNameFilter().filter(indexName) &&
                        indexMetadata.getIndexNameFilter().getNamePart(indexName).equalsIgnoreCase(indexMetadata.getIndexName())) {

                    //Extract date from Index Name
                    int indexDate = IndexUtils.getDateFromIndexName(indexMetadata, indexName);
                    if(config.isDebugEnabled())
                        logger.debug("Date extracted from Index <" + indexName + "> = <" + indexDate + ">");
                    //Delete old indices
                    if (indexDate <= pastRetentionCutoffDateDate) {
                        if(config.isDebugEnabled())
                            logger.debug("Date extracted from index <" + indexDate + "> is past the retention date <" + pastRetentionCutoffDateDate + ", hence deleting index now.");
                        deleteIndices(esTransportClient, indexName, config.getAutoCreateIndexTimeout());
                    }
                }
            }
        }
        else{
            if(config.isDebugEnabled())
                logger.debug("Indexes Map is empty ... No Indices found");
        }
    }

    /**
     * Courtesy Jae Bae
     */
    public void deleteIndices(Client client, String indexName, int timeout) {
        logger.info("trying to delete " + indexName);
        DeleteIndexResponse deleteIndexResponse = client.admin().indices().prepareDelete(indexName).execute().actionGet(timeout);
        if (!deleteIndexResponse.isAcknowledged()) {
            throw new RuntimeException("INDEX DELETION FAILED");
        } else {
            logger.info(indexName + " deleted");
        }
    }

    /**
     * Courtesy Jae Bae
     */
    public void preCreateIndex(IndexMetadata indexMetadata,TransportClient esTransportClient) throws UnsupportedAutoIndexException {
        IndicesStatusResponse getIndicesResponse = esTransportClient.admin().indices().prepareStatus().execute().actionGet(config.getAutoCreateIndexTimeout());
        Map<String, IndexStatus> indexStatusMap = getIndicesResponse.getIndices();
        if (!indexStatusMap.isEmpty()) {
            for (String indexNameWithDateSuffix : indexStatusMap.keySet()) {
                if(config.isDebugEnabled())
                    logger.debug("Index Name = <" + indexNameWithDateSuffix + ">");
                if (indexMetadata.getIndexNameFilter().filter(indexNameWithDateSuffix) &&
                        indexMetadata.getIndexNameFilter().getNamePart(indexNameWithDateSuffix).equalsIgnoreCase(indexMetadata.getIndexName())) {

                    for (int i = 0; i < indexMetadata.getRetentionPeriod(); ++i) {

                        DateTime dt = new DateTime();
                        int addedDate;

                        switch (indexMetadata.getRetentionType()) {
                            case DAILY:
                                dt = dt.plusDays(i);
                                addedDate = Integer.parseInt(String.format("%d%02d%02d", dt.getYear(), dt.getMonthOfYear(), dt.getDayOfMonth()));
                                break;
                            case MONTHLY:
                                dt = dt.plusMonths(i);
                                addedDate = Integer.parseInt(String.format("%d%02d", dt.getYear(), dt.getMonthOfYear()));
                                break;
                            case YEARLY:
                                dt = dt.plusYears(i);
                                addedDate = Integer.parseInt(String.format("%d", dt.getYear()));
                                break;
                            default:
                                throw new UnsupportedAutoIndexException("Given index is not (DAILY or MONTHLY or YEARLY), please check your configuration.");

                        }

                        if(config.isDebugEnabled())
                            logger.debug("Future Date = " + addedDate);

                        if (!esTransportClient.admin().indices().prepareExists(indexMetadata.getIndexName() + addedDate).execute().actionGet(config.getAutoCreateIndexTimeout()).isExists()) {
                            esTransportClient.admin().indices().prepareCreate(indexMetadata.getIndexName() + addedDate).execute().actionGet(config.getAutoCreateIndexTimeout());
                            logger.info(indexMetadata.getIndexName() + addedDate + " is created");
                        } else {
                            //TODO: Change to Debug after Testing
                            logger.warn(indexMetadata.getIndexName() + addedDate + " already exists");
                        }
                    }
                }
            }
        }
    }
}
