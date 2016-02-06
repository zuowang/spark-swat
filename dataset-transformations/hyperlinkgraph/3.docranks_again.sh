#!/bin/bash

set -e

# ${HADOOP_HOME}/bin/hdfs dfs -rm -f -r /renormalized-links
${HADOOP_HOME}/bin/hdfs dfs -rm -f -r /doc-ranks

# ${HADOOP_HOME}/bin/hdfs dfs -mkdir /renormalized-links
# ${HADOOP_HOME}/bin/hdfs dfs -put \
#         $SPARK_DATASETS/hyperlinkgraph/3.renormalize/part* /renormalized-links/

spark-submit --class DocRankGenerator \
        --jars ${SWAT_HOME}/swat/target/swat-1.0-SNAPSHOT.jar,${APARAPI_SWAT}/com.amd.aparapi/dist/aparapi.jar,${ASM_HOME}/lib/asm-5.0.3.jar,${ASM_HOME}/lib/asm-util-5.0.3.jar \
        --master spark://localhost:7077 --conf "spark.driver.maxResultSize=4g" \
        --conf "spark.storage.memoryFraction=0.3" \
        ${SWAT_HOME}/dataset-transformations/hyperlinkgraph/target/hyperlink-graph-0.0.0.jar \
        hdfs://$(hostname):54310/renormalized-links hdfs://$(hostname):54310/doc-ranks

rm -rf $SPARK_DATASETS/hyperlinkgraph/3.docranks_again
${HADOOP_HOME}/bin/hdfs dfs -get /doc-ranks $SPARK_DATASETS/hyperlinkgraph/3.docranks_again
