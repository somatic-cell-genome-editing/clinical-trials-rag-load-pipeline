#!/usr/bin/env bash
#
# Clinical Trials RAG Load Pipeline
# Updates clinical trial embeddings in vector database
#
. /etc/profile

APPNAME="clinical-trials-rag-load-pipeline"
APPDIR="/data/pipelines/$APPNAME"
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
EMAIL_LIST="akundurthi@mcw.edu"

# Additional email for production server
if [ "$SERVER" = "LEETA" ]; then
  EMAIL_LIST="$EMAIL_LIST"
fi

cd $APPDIR
pwd

# Database configuration from default_db.xml
DB_OPTS="-DSPRING_CONFIG=/data/pipelines/properties/default_db.xml"
LOG4J_OPTS="-Dlogging.config=file://$APPDIR/properties/log4j2.xml -Dlog.dir=$APPDIR/logs"
export CLINICAL_TRIALS_RAG_PIPELINE_OPTS="$DB_OPTS $LOG4J_OPTS"

# Run the pipeline using bin script (created by Gradle application plugin)
bin/$APPNAME "$@" | tee run.log

# Send email notification with log files
if [ -f "$APPDIR/logs/status.log" ]; then
  (echo "=== Status Log ===" && cat $APPDIR/logs/status.log && echo -e "\n\n=== Run Log ===" && cat run.log) | \
    mailx -s "[$SERVER] Clinical Trials RAG Load Pipeline Run" $EMAIL_LIST
else
  mailx -s "[$SERVER] Clinical Trials RAG Load Pipeline Run" $EMAIL_LIST < run.log
fi
