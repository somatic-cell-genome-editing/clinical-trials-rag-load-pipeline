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
  EMAIL_LIST="$EMAIL_LIST rgddata@travis.rgd.mcw.edu"
fi

cd $APPDIR

# Create logs directory if it doesn't exist
mkdir -p $APPDIR/logs

# Database configuration from default_db.xml
DB_OPTS="-Dspring.config=/data/pipelines/properties/default_db.xml"

# Run the pipeline
java $DB_OPTS \
    -Dspring.config.location=file:$APPDIR/properties/application.properties \
    -Dlogging.config=file:$APPDIR/properties/log4j2.xml \
    -Dlog.dir=$APPDIR/logs \
    -jar lib/$APPNAME.jar "$@" > run.log 2>&1

# Send email notification with log file
mailx -s "[$SERVER] Clinical Trials RAG Load Pipeline Run" $EMAIL_LIST < $APPDIR/logs/status.log
