/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2015 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.job.entries.spark;

import static org.pentaho.di.job.entry.validator.AndValidator.putValidators;
import static org.pentaho.di.job.entry.validator.JobEntryValidatorUtils.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.annotations.VisibleForTesting;
import org.pentaho.di.cluster.SlaveServer;
import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.annotations.JobEntry;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleXMLException;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobEntryListener;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryBase;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.Repository;
import org.pentaho.metastore.api.IMetaStore;
import org.w3c.dom.Node;

/**
 * This job entry submits a JAR to Spark and executes a class. It uses the spark-submit script to submit a command like
 * this: spark-submit --class org.pentaho.spark.SparkExecTest --master yarn-cluster my-spark-job.jar arg1 arg2
 *
 * More information on the options is here: http://spark.apache.org/docs/1.2.0/submitting-applications.html
 *
 * @author jdixon
 * @since Dec 3 2014
 *
 */

@JobEntry( image = "org/pentaho/di/ui/job/entries/spark/img/spark.svg", id = "SparkSubmit",
    name = "JobEntrySparkSubmit.Title", description = "JobEntrySparkSubmit.Description",
    categoryDescription = "i18n:org.pentaho.di.job:JobCategory.Category.BigData",
    i18nPackageName = "org.pentaho.di.job.entries.spark",
    documentationUrl = "http://wiki.pentaho.com/display/EAI/Spark+Submit" )
public class JobEntrySparkSubmit extends JobEntryBase implements Cloneable, JobEntryInterface, JobEntryListener {
  private static Class<?> PKG = JobEntrySparkSubmit.class; // for i18n purposes, needed by Translator2!!

  private String scriptPath; // the path for the spark-submit utility
  private String master = "yarn-cluster"; // the URL for the Spark master
  private List<String> configParams = new ArrayList<String>(); // configuration options, "key=value"
  private String jar; // the path for the jar containing the Spark code to run
  private String className; // the name of the class to run
  private String args; // arguments for the Spark code
  private boolean blockExecution = true; // wait for job to complete
  private String executorMemory; // memory allocation config param for the executor
  private String driverMemory; // memory allocation config param for the driver

  protected Process proc; // the process for the spark-submit command

  public JobEntrySparkSubmit( String n ) {
    super( n, "" );
  }

  public JobEntrySparkSubmit() {
    this( "" );
  }

  public Object clone() {
    JobEntrySparkSubmit je = (JobEntrySparkSubmit) super.clone();
    return je;
  }

  /**
   * Converts the state into XML and returns it
   *
   * @return The XML for the current state
   */
  public String getXML() {
    StringBuffer retval = new StringBuffer( 200 );

    retval.append( super.getXML() );
    retval.append( "      " ).append( XMLHandler.addTagValue( "scriptPath", scriptPath ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "master", master ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "jar", jar ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "className", className ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "args", args ) );
    retval.append( "      " ).append( XMLHandler.openTag( "configParams" ) ).append( Const.CR );
    for ( String param : configParams ) {
      retval.append( "            " ).append( XMLHandler.addTagValue( "param", param ) );
    }

    retval.append( "      " ).append( XMLHandler.closeTag( "configParams" ) ).append( Const.CR );
    retval.append( "      " ).append( XMLHandler.addTagValue( "driverMemory", driverMemory ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "executorMemory", executorMemory ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "blockExecution", blockExecution ) );
    return retval.toString();
  }

  /**
   * Parses XML and recreates the state
   *
   */
  public void loadXML( Node entrynode, List<DatabaseMeta> databases, List<SlaveServer> slaveServers, Repository rep,
      IMetaStore metaStore ) throws KettleXMLException {
    try {
      super.loadXML( entrynode, databases, slaveServers );

      scriptPath = XMLHandler.getTagValue( entrynode, "scriptPath" );
      master = XMLHandler.getTagValue( entrynode, "master" );
      jar = XMLHandler.getTagValue( entrynode, "jar" );
      className = XMLHandler.getTagValue( entrynode, "className" );
      args = XMLHandler.getTagValue( entrynode, "args" );
      Node configParamsNode = XMLHandler.getSubNode( entrynode, "configParams" );
      List<Node> paramNodes = XMLHandler.getNodes( configParamsNode, "param" );
      for ( Node paramNode : paramNodes ) {
        configParams.add( paramNode.getTextContent() );
      }
      driverMemory = XMLHandler.getTagValue( entrynode, "driverMemory" );
      executorMemory = XMLHandler.getTagValue( entrynode, "executorMemory" );
      blockExecution = "Y".equalsIgnoreCase( XMLHandler.getTagValue( entrynode, "blockExecution" ) );
    } catch ( KettleXMLException xe ) {
      throw new KettleXMLException( "Unable to load job entry of type 'SparkSubmit' from XML node", xe );
    }
  }

  /**
   * Reads the state from the repository
   */
  public void loadRep( Repository rep, IMetaStore metaStore, ObjectId id_jobentry, List<DatabaseMeta> databases,
      List<SlaveServer> slaveServers ) throws KettleException {
    try {
      scriptPath = rep.getJobEntryAttributeString( id_jobentry, "scriptPath" );
      master = rep.getJobEntryAttributeString( id_jobentry, "master" );
      jar = rep.getJobEntryAttributeString( id_jobentry, "jar" );
      className = rep.getJobEntryAttributeString( id_jobentry, "className" );
      args = rep.getJobEntryAttributeString( id_jobentry, "args" );
      for ( int i = 0; i < rep.countNrJobEntryAttributes( id_jobentry, "param" ); i++ ) {
        configParams.add( rep.getJobEntryAttributeString( id_jobentry, i, "param" ) );
      }
      driverMemory = rep.getJobEntryAttributeString( id_jobentry, "driverMemory" );
      executorMemory = rep.getJobEntryAttributeString( id_jobentry, "executorMemory" );
      blockExecution = rep.getJobEntryAttributeBoolean( id_jobentry, "blockExecution" );
    } catch ( KettleException dbe ) {
      throw new KettleException( "Unable to load job entry of type 'SparkSubmit' from the repository for id_jobentry="
          + id_jobentry, dbe );
    }
  }

  /**
   * Saves the current state into the repository
   */
  public void saveRep( Repository rep, IMetaStore metaStore, ObjectId id_job ) throws KettleException {
    try {
      rep.saveJobEntryAttribute( id_job, getObjectId(), "scriptPath", scriptPath );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "master", master );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "jar", jar );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "className", className );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "args", args );
      for ( int i = 0; i < configParams.size(); i++ ) {
        rep.saveJobEntryAttribute( id_job, getObjectId(), i, "param", configParams.get( i ) );
      }
      rep.saveJobEntryAttribute( id_job, getObjectId(), "driverMemory", driverMemory );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "executorMemory", executorMemory );
      rep.saveJobEntryAttribute( id_job, getObjectId(), "blockExecution", blockExecution );
    } catch ( KettleDatabaseException dbe ) {
      throw new KettleException( "Unable to save job entry of type 'SparkSubmit' to the repository for id_job="
          + id_job, dbe );
    }
  }

  /**
   * Returns the path for the spark-submit utility
   *
   * @return The script path
   */
  public String getScriptPath() {
    return scriptPath;
  }

  /**
   * Sets the path for the spark-submit utility
   *
   * @param scriptPath
   *          path to spark-submit utility
   */
  public void setScriptPath( String scriptPath ) {
    this.scriptPath = scriptPath;
  }

  /**
   * Returns the URL for the Spark master node
   *
   * @return The URL for the Spark master node
   */
  public String getMaster() {
    return master;
  }

  /**
   * Sets the URL for the Spark master node
   *
   * @param master
   *          URL for the Spark master node
   */
  public void setMaster( String master ) {
    this.master = master;
  }

  /**
   * Returns map of configuration params
   *
   * @return map of configuration params
   */
  public List<String> getConfigParams() {
    return configParams;
  }

  /**
   * Sets configuration params
   */
  public void setConfigParams( List<String> configParams ) {
    this.configParams = configParams;
  }

  /**
   * Returns the path for the jar containing the Spark code to execute
   *
   * @return The path for the jar
   */
  public String getJar() {
    return jar;
  }

  /**
   * Sets the path for the jar containing the Spark code to execute
   *
   * @param jar
   *          path for the jar
   */
  public void setJar( String jar ) {
    this.jar = jar;
  }

  /**
   * Returns the name of the class containing the Spark code to execute
   *
   * @return The name of the class
   */
  public String getClassName() {
    return className;
  }

  /**
   * Sets the name of the class containing the Spark code to execute
   *
   * @param className
   *          name of the class
   */
  public void setClassName( String className ) {
    this.className = className;
  }

  /**
   * Returns the arguments for the Spark class. This is a space-separated list of strings, e.g. "http.log 1000"
   *
   * @return The arguments
   */
  public String getArgs() {
    return args;
  }

  /**
   * Sets the arguments for the Spark class. This is a space-separated list of strings, e.g. "http.log 1000"
   *
   * @param args
   *          arguments
   */
  public void setArgs( String args ) {
    this.args = args;
  }

  /**
   * Returns executor memory config param's value
   *
   * @return executor memory config param
   */
  public String getExecutorMemory() {
    return executorMemory;
  }

  /**
   * Sets executor memory config param's value
   *
   * @param executorMemory
   *          amount of memory executor process is allowed to consume
   */
  public void setExecutorMemory( String executorMemory ) {
    this.executorMemory = executorMemory;
  }

  /**
   * Returns driver memory config param's value
   *
   * @return driver memory config param
   */
  public String getDriverMemory() {
    return driverMemory;
  }

  /**
   * Sets driver memory config param's value
   *
   * @param driverMemory
   *          amount of memory driver process is allowed to consume
   */
  public void setDriverMemory( String driverMemory ) {
    this.driverMemory = driverMemory;
  }

  /**
   * Returns if the job entry will wait till job execution completes
   *
   * @return blocking mode
   */
  public boolean isBlockExecution() {
    return blockExecution;
  }

  /**
   * Sets if the job entry will wait for job execution to complete
   *
   * @param blockExecution
   *          blocking mode
   */
  public void setBlockExecution( boolean blockExecution ) {
    this.blockExecution = blockExecution;
  }

  /**
   * Returns the spark-submit command as a list of strings. e.g. <path to spark-submit> --class <main-class> --master
   * <master-url> --deploy-mode <deploy-mode> --conf <key>=<value> <application-jar> \ [application-arguments]
   *
   * @return The spark-submit command
   */
  public List<String> getCmds() {
    List<String> cmds = new ArrayList<String>();

    cmds.add( environmentSubstitute( scriptPath ) );
    cmds.add( "--master" );
    cmds.add( environmentSubstitute( master ) );

    if ( !Const.isEmpty( className ) ) {
      cmds.add( "--class" );
      cmds.add( environmentSubstitute( className ) );
    }

    for ( String confParam : configParams ) {
      cmds.add( "--conf" );
      cmds.add( environmentSubstitute( confParam ) );
    }

    if ( !Const.isEmpty( driverMemory ) ) {
      cmds.add( "--driver-memory" );
      cmds.add( environmentSubstitute( driverMemory ) );
    }

    if ( !Const.isEmpty( executorMemory ) ) {
      cmds.add( "--executor-memory" );
      cmds.add( environmentSubstitute( executorMemory ) );
    }

    cmds.add( jar );

    if ( !Const.isEmpty( args ) ) {
      String[] argArray = environmentSubstitute( args ).split( " " );
      for ( String anArgArray : argArray ) {
        if ( !Const.isEmpty( anArgArray ) ) {
          cmds.add( anArgArray );
        }
      }
    }

    return cmds;
  }

  @VisibleForTesting
  protected boolean validate ( ) {
    boolean valid = true;
    if ( Const.isEmpty( scriptPath ) || !new File( environmentSubstitute( scriptPath ) ).exists() ) {
      logError( BaseMessages.getString( PKG, "JobEntrySparkSubmit.Error.SparkSubmitPathInvalid" ) );
      valid = false;
    }

    if ( Const.isEmpty( master ) ) {
      logError( BaseMessages.getString( PKG, "JobEntrySparkSubmit.Error.MasterURLEmpty" ) );
      valid = false;
    }
    if ( Const.isEmpty( jar ) ) {
      logError( BaseMessages.getString( PKG, "JobEntrySparkSubmit.Error.JarPathEmpty" ) );
      valid = false;
    }

    return valid;
  }

  /**
   * Executes the spark-submit command and returns a Result
   *
   * @return The Result of the operation
   */
  public Result execute( Result result, int nr ) {

    if ( !validate() ) {
      result.setResult( false );
      return result;
    }

    List<String> cmds = getCmds();

    logBasic( "Submitting Spark Script" );

    if ( log.isDetailed() ) {
      logDetailed( cmds.toString() );
    }

    try {
      // Build the environment variable list...
      ProcessBuilder procBuilder = new ProcessBuilder( cmds );
      Map<String, String> env = procBuilder.environment();
      String[] variables = listVariables();
      for ( String variable : variables ) {
        env.put( variable, getVariable( variable ) );
      }
      proc = procBuilder.start();

      String[] jobSubmittedPatterns = new String[] { "tracking URL:" };

      final AtomicBoolean jobSubmitted = new AtomicBoolean( false );

      // any error message?
      PatternMatchingStreamLogger errorLogger =
          new PatternMatchingStreamLogger( log, proc.getErrorStream(), jobSubmittedPatterns, jobSubmitted );

      // any output?
      PatternMatchingStreamLogger outputLogger =
          new PatternMatchingStreamLogger( log, proc.getInputStream(), jobSubmittedPatterns, jobSubmitted );

      if ( !blockExecution ) {
        PatternMatchingStreamLogger.PatternMatchedListener cb =
            new PatternMatchingStreamLogger.PatternMatchedListener() {
              @Override
              public void onPatternFound( String pattern ) {
                log.logDebug( "Found match in output, considering job submitted, stopping spark-submit" );
                jobSubmitted.set( true );
                proc.destroy();
              }
            };
        errorLogger.addPatternMatchedListener( cb );
        outputLogger.addPatternMatchedListener( cb );
      }

      // kick them off
      Thread errorLoggerThread = new Thread( errorLogger );
      errorLoggerThread.start();
      Thread outputLoggerThread = new Thread( outputLogger );
      outputLoggerThread.start();

      // Stop on job stop
      final AtomicBoolean processFinished = new AtomicBoolean( false );
      new Thread( new Runnable() {
        @Override
        public void run() {
          while ( !getParentJob().isStopped() && !processFinished.get() ) {
            try {
              Thread.sleep( 5000 );
            } catch ( InterruptedException e ) {
              e.printStackTrace();
            }
          }
          proc.destroy();
        }
      } ).start();

      proc.waitFor();
      processFinished.set( true );

      if ( log.isDetailed() ) {
        logDetailed( "Spark submit finished" );
      }

      // wait until loggers read all data from stdout and stderr
      errorLoggerThread.join();
      outputLoggerThread.join();

      // close the streams
      // otherwise you get "Too many open files, java.io.IOException" after a lot of iterations
      proc.getErrorStream().close();
      proc.getOutputStream().close();

      // What's the exit status?
      int exitCode;
      if ( blockExecution ) {
        exitCode = proc.exitValue();
      } else {
        exitCode = jobSubmitted.get() ? 0 : proc.exitValue();
      }

      result.setExitStatus( exitCode );
      if ( exitCode != 0 ) {
        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString( PKG, "JobEntrySparkSubmit.ExitStatus", result.getExitStatus() ) );
        }

        result.setNrErrors( 1 );
      }

      result.setResult( exitCode == 0 );
    } catch ( Exception e ) {
      result.setNrErrors( 1 );
      logError( BaseMessages.getString( PKG, "JobEntrySparkSubmit.Error.SubmittingScript", e.getMessage() ) );
      logError( Const.getStackTracker( e ) );
      result.setResult( false );
    }

    return result;
  }

  public boolean evaluates() {
    return true;
  }

  /**
   * Checks that the minimum options have been provided.
   */
  @Override
  public void check( List<CheckResultInterface> remarks, JobMeta jobMeta, VariableSpace space, Repository repository,
      IMetaStore metaStore ) {
    andValidator().validate( this, "scriptPath", remarks, putValidators( notBlankValidator() ) );
    andValidator().validate( this, "scriptPath", remarks, putValidators( fileExistsValidator() ) );
    andValidator().validate( this, "master", remarks, putValidators( notBlankValidator() ) );
    andValidator().validate( this, "jar", remarks, putValidators( notBlankValidator() ) );
    andValidator().validate( this, "className", remarks, putValidators( notBlankValidator() ) );
  }

  public static void main( String[] args ) {
    List<CheckResultInterface> remarks = new ArrayList<CheckResultInterface>();
    new JobEntrySparkSubmit().check( remarks, null, new Variables(), null, null );
    System.out.printf( "Remarks: %s\n", remarks );
  }

  @Override
  public void afterExecution( Job arg0, JobEntryCopy arg1, JobEntryInterface arg2, Result arg3 ) {
    proc.destroy();
  }

  @Override
  public void beforeExecution( Job arg0, JobEntryCopy arg1, JobEntryInterface arg2 ) {
  }
}
