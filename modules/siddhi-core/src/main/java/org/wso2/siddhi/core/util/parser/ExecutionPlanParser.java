/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.siddhi.core.util.parser;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.config.SiddhiContext;
import org.wso2.siddhi.core.exception.ExecutionPlanCreationException;
import org.wso2.siddhi.core.partition.PartitionRuntime;
import org.wso2.siddhi.core.query.QueryRuntime;
import org.wso2.siddhi.core.util.ElementIdGenerator;
import org.wso2.siddhi.core.util.ExecutionPlanRuntimeBuilder;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.ThreadBarrier;
import org.wso2.siddhi.core.util.persistence.PersistenceService;
import org.wso2.siddhi.core.util.snapshot.SnapshotService;
import org.wso2.siddhi.core.util.statistics.LatencyTracker;
import org.wso2.siddhi.core.util.timestamp.EventTimeBasedMillisTimestampGenerator;
import org.wso2.siddhi.core.util.timestamp.SystemCurrentTimeMillisTimestampGenerator;
import org.wso2.siddhi.core.window.Window;
import org.wso2.siddhi.query.api.ExecutionPlan;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.FunctionDefinition;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.definition.TriggerDefinition;
import org.wso2.siddhi.query.api.definition.WindowDefinition;
import org.wso2.siddhi.query.api.exception.DuplicateAnnotationException;
import org.wso2.siddhi.query.api.exception.DuplicateDefinitionException;
import org.wso2.siddhi.query.api.exception.ExecutionPlanValidationException;
import org.wso2.siddhi.query.api.execution.ExecutionElement;
import org.wso2.siddhi.query.api.execution.partition.Partition;
import org.wso2.siddhi.query.api.execution.query.Query;
import org.wso2.siddhi.query.api.util.AnnotationHelper;
import org.wso2.siddhi.query.compiler.SiddhiCompiler;
import org.wso2.siddhi.query.compiler.exception.SiddhiParserException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Class to parse {@link ExecutionPlan}
 */
public class ExecutionPlanParser {
    private static final Logger log = Logger.getLogger(ExecutionPlanRuntimeBuilder.class);

    /**
     * Parse an ExecutionPlan returning ExecutionPlanRuntime
     *
     * @param executionPlan plan to be parsed
     * @param siddhiContext SiddhiContext
     * @return ExecutionPlanRuntime
     */
    public static ExecutionPlanRuntimeBuilder parse(ExecutionPlan executionPlan, SiddhiContext siddhiContext) {

        ExecutionPlanContext executionPlanContext = new ExecutionPlanContext();
        executionPlanContext.setSiddhiContext(siddhiContext);

        try {
            Element element = AnnotationHelper.getAnnotationElement(SiddhiConstants.ANNOTATION_NAME, null,
                                                                    executionPlan.getAnnotations());
            if (element != null) {
                executionPlanContext.setName(element.getValue());
            } else {
                executionPlanContext.setName(UUID.randomUUID().toString());
            }

            Annotation annotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_ENFORCE_ORDER,
                                                                   executionPlan.getAnnotations());
            if (annotation != null) {
                executionPlanContext.setEnforceOrder(true);
            }

            annotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_ASYNC,
                                                        executionPlan.getAnnotations());
            if (annotation != null) {
                executionPlanContext.setAsync(true);
                String bufferSizeString = annotation.getElement(SiddhiConstants.ANNOTATION_ELEMENT_BUFFER_SIZE);
                if (bufferSizeString != null) {
                    int bufferSize = Integer.parseInt(bufferSizeString);
                    executionPlanContext.setBufferSize(bufferSize);
                } else {
                    executionPlanContext.setBufferSize(SiddhiConstants.DEFAULT_EVENT_BUFFER_SIZE);
                }
            }

            annotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_STATISTICS,
                                                        executionPlan.getAnnotations());

            Element statElement = AnnotationHelper.getAnnotationElement(SiddhiConstants.ANNOTATION_STATISTICS, null,
                                                                        executionPlan.getAnnotations());

            // Both annotation and statElement should be checked since siddhi uses
            // @plan:statistics(reporter = 'console', interval = '5' )
            // where cep uses @plan:statistics('true').
            if (annotation != null && (statElement == null || Boolean.valueOf(statElement.getValue()))) {
                if (siddhiContext.getStatisticsConfiguration() != null) {
                    executionPlanContext.setStatsEnabled(true);
                    executionPlanContext.setStatisticsManager(siddhiContext
                                                                      .getStatisticsConfiguration()
                                                                      .getFactory()
                                                                      .createStatisticsManager(annotation.getElements()));
                }
            }

            executionPlanContext.setThreadBarrier(new ThreadBarrier());

            executionPlanContext.setExecutorService(Executors.newCachedThreadPool(
                    new ThreadFactoryBuilder().setNameFormat("Siddhi-" + executionPlanContext.getName() +
                                                                     "-executor-thread-%d").build()));

            executionPlanContext.setScheduledExecutorService(Executors.newScheduledThreadPool(5,
                                                                                              new ThreadFactoryBuilder().setNameFormat("Siddhi-" +
                                                                                                                                               executionPlanContext.getName() + "-scheduler-thread-%d").build()));

            // Select the TimestampGenerator based on playback mode on/off
            annotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_PLAYBACK,
                                                        executionPlan.getAnnotations());
            if (annotation != null) {
                String idleTime = null;
                String increment = null;
                EventTimeBasedMillisTimestampGenerator timestampGenerator = new
                        EventTimeBasedMillisTimestampGenerator(executionPlanContext.getScheduledExecutorService());
                // Get the optional elements of playback annotation
                for (Element e : annotation.getElements()) {
                    if (SiddhiConstants.ANNOTATION_ELEMENT_IDLE_TIME.equalsIgnoreCase(e.getKey())) {
                        idleTime = e.getValue();
                    } else if (SiddhiConstants.ANNOTATION_ELEMENT_INCREMENT.equalsIgnoreCase(e.getKey())) {
                        increment = e.getValue();
                    } else {
                        throw new ExecutionPlanValidationException("Playback annotation accepts only idle.time and " +
                                                                           "increment but found " + e.getKey());
                    }
                }

                // idleTime and increment are optional but if one presents, the other also should be given
                if (idleTime != null && increment == null) {
                    throw new ExecutionPlanValidationException("Playback annotation requires both idle.time and " +
                                                                       "increment but increment not found");
                } else if (idleTime == null && increment != null) {
                    throw new ExecutionPlanValidationException("Playback annotation requires both idle.time and " +
                                                                       "increment but idle.time does not found");
                } else if (idleTime != null) {
                    // The fourth case idleTime == null && increment == null are ignored because it means no heartbeat.
                    try {
                        timestampGenerator.setIdleTime(SiddhiCompiler.parseTimeConstantDefinition(idleTime).value());
                    } catch (SiddhiParserException ex) {
                        throw new SiddhiParserException("Invalid idle.time constant '" + idleTime + "' in playback " +
                                                                "annotation", ex);
                    }
                    try {
                        timestampGenerator.setIncrementInMilliseconds(SiddhiCompiler.parseTimeConstantDefinition
                                (increment).value());
                    } catch (SiddhiParserException ex) {
                        throw new SiddhiParserException("Invalid increment constant '" + increment + "' in playback " +
                                                                "annotation", ex);
                    }
                }

                executionPlanContext.setTimestampGenerator(timestampGenerator);
                executionPlanContext.setPlayback(true);
            } else {
                executionPlanContext.setTimestampGenerator(new SystemCurrentTimeMillisTimestampGenerator());
            }
            executionPlanContext.setSnapshotService(new SnapshotService(executionPlanContext));
            executionPlanContext.setPersistenceService(new PersistenceService(executionPlanContext));
            executionPlanContext.setElementIdGenerator(new ElementIdGenerator(executionPlanContext.getName()));

        } catch (DuplicateAnnotationException e) {
            throw new DuplicateAnnotationException(e.getMessage() + " for the same Execution Plan " +
                                                           executionPlan.toString());
        }

        ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder = new ExecutionPlanRuntimeBuilder(executionPlanContext);

        defineStreamDefinitions(executionPlanRuntimeBuilder, executionPlan.getStreamDefinitionMap());
        defineTableDefinitions(executionPlanRuntimeBuilder, executionPlan.getTableDefinitionMap());
        defineWindowDefinitions(executionPlanRuntimeBuilder, executionPlan.getWindowDefinitionMap());
        defineFunctionDefinitions(executionPlanRuntimeBuilder, executionPlan.getFunctionDefinitionMap());
        for (Window window : executionPlanRuntimeBuilder.getEventWindowMap().values()) {
            String metricName =
                    executionPlanContext.getSiddhiContext().getStatisticsConfiguration().getMatricPrefix() +
                            SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_EXECUTION_PLANS +
                            SiddhiConstants.METRIC_DELIMITER + executionPlanContext.getName() +
                            SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_SIDDHI +
                            SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_WINDOWS +
                            SiddhiConstants.METRIC_DELIMITER + window.getWindowDefinition().getId();
            LatencyTracker latencyTracker = null;
            if (executionPlanContext.isStatsEnabled() && executionPlanContext.getStatisticsManager() != null) {
                latencyTracker = executionPlanContext.getSiddhiContext()
                        .getStatisticsConfiguration()
                        .getFactory()
                        .createLatencyTracker(metricName, executionPlanContext.getStatisticsManager());
            }
            window.init(executionPlanRuntimeBuilder.getTableMap(), executionPlanRuntimeBuilder
                    .getEventWindowMap(), latencyTracker, window.getWindowDefinition().getId());
        }
        try {
            for (ExecutionElement executionElement : executionPlan.getExecutionElementList()) {
                if (executionElement instanceof Query) {
                    QueryRuntime queryRuntime = QueryParser.parse((Query) executionElement, executionPlanContext,
                                                                  executionPlanRuntimeBuilder.getStreamDefinitionMap(),
                                                                  executionPlanRuntimeBuilder.getTableDefinitionMap(),
                                                                  executionPlanRuntimeBuilder.getWindowDefinitionMap(),
                                                                  executionPlanRuntimeBuilder.getTableMap(),
                                                                  executionPlanRuntimeBuilder.getEventWindowMap(),
                                                                  executionPlanRuntimeBuilder.getEventSourceMap(),
                                                                  executionPlanRuntimeBuilder.getEventSinkMap(),
                                                                  executionPlanRuntimeBuilder.getLockSynchronizer());
                    executionPlanRuntimeBuilder.addQuery(queryRuntime);
                } else {
                    PartitionRuntime partitionRuntime = PartitionParser.parse(executionPlanRuntimeBuilder,
                                                                              (Partition) executionElement, executionPlanContext,
                                                                              executionPlanRuntimeBuilder.getStreamDefinitionMap());
                    executionPlanRuntimeBuilder.addPartition(partitionRuntime);
                }
            }
        } catch (ExecutionPlanCreationException e) {
            throw new ExecutionPlanValidationException(e.getMessage() + " in execution plan \"" +
                                                               executionPlanContext.getName() + "\"", e);
        } catch (DuplicateDefinitionException e) {
            throw new DuplicateDefinitionException(e.getMessage() + " in execution plan \"" +
                                                           executionPlanContext.getName() + "\"", e);
        }

        //Done last as they have to be started last
        defineTriggerDefinitions(executionPlanRuntimeBuilder, executionPlan.getTriggerDefinitionMap());
        return executionPlanRuntimeBuilder;
    }

    private static void defineTriggerDefinitions(ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder,
                                                 Map<String, TriggerDefinition> triggerDefinitionMap) {
        for (TriggerDefinition definition : triggerDefinitionMap.values()) {
            executionPlanRuntimeBuilder.defineTrigger(definition);
        }
    }

    private static void defineFunctionDefinitions(ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder,
                                                  Map<String, FunctionDefinition> functionDefinitionMap) {
        for (FunctionDefinition definition : functionDefinitionMap.values()) {
            executionPlanRuntimeBuilder.defineFunction(definition);
        }
    }

    private static void defineStreamDefinitions(ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder,
                                                Map<String, StreamDefinition> streamDefinitionMap) {
        for (StreamDefinition definition : streamDefinitionMap.values()) {
            executionPlanRuntimeBuilder.defineStream(definition);
        }
    }

    private static void defineTableDefinitions(ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder,
                                               Map<String, TableDefinition> tableDefinitionMap) {
        for (TableDefinition definition : tableDefinitionMap.values()) {
            executionPlanRuntimeBuilder.defineTable(definition);
        }
    }

    private static void defineWindowDefinitions(ExecutionPlanRuntimeBuilder executionPlanRuntimeBuilder,
                                                Map<String, WindowDefinition> windowDefinitionMap) {
        for (WindowDefinition definition : windowDefinitionMap.values()) {
            executionPlanRuntimeBuilder.defineWindow(definition);
        }
    }
}
