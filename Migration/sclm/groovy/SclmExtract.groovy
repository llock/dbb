import com.ibm.dbb.*
import com.ibm.dbb.build.*
import com.ibm.jzos.*
import java.nio.file.*
import groovy.transform.SourceURI

/*******************************************************************************
 * 
 * This script is used to extract information from SCLM to temporary locations
 * (data sets and HFS files).
 * 
 * These are the information being extracted from SCLM:
 * 1. It first parses the information from the sclmmig.config.
 * 2. If user specifies a "step" to execute then skip to the next, otherwise
 *    it determines which "step" to execute based on the existence of the 
 *    output files.
 * 3. Deletes any existing output files in case the previous run failed.
 * 4. Copy the Rexx execute associated with this "step" to the temporary
 *    data set along with the sclmmig.config.
 * 5. Invoke ISPFExec to run the Rexx execute from the data set.       
 * 
 *
 *******************************************************************************/

//******************************************************************************
//* Retrieves DBB environments
//******************************************************************************
dbbHome = System.getenv("DBB_HOME")
if (!dbbHome)
{
    println "Need to specified the required environment 'DBB_HOME'"
    System.exit(1)
}
dbbConf = System.getenv("DBB_CONF")?:EnvVars.getConf()

//******************************************************************************
//* Parses and validates the input arguments 
//******************************************************************************
def headerMsg = 'Extract information of a SCLM project to the temporary data sets and files on HFS'
cli = new CliBuilder(usage: 'SclmExtract [options] sclmmig.config', header: headerMsg, stopAtNonOption: false)
cli.x(longOpt:'exec', args:1, argName:'rexxExec', 'The target executed REXX')
cli.h(longOpt:'help', 'Prints this message')

def parameters = cli.parse(args)
if (parameters.arguments().size() < 1)
{
    cli.usage()
    System.exit(2)
}

@SourceURI
URI sourceUri
Path scriptLocation = Paths.get(sourceUri).parent

//******************************************************************************
//* Parses the SCLM migration config file
//******************************************************************************
def configFile = scriptLocation.resolve(parameters.arguments()[0]).toFile()
if (!configFile.exists())
{
    println "File $configFile does not exist. Need to specify a valid SCLM migration config file"
    System.exit(1)
}

def config = new Properties()
config.load(configFile.newDataInputStream())

def proj = config.proj
def group = config.group
def migHlq = (config.migHlq?:"whoami".execute().text).trim()
def tempHlq = "${migHlq}.SCLMMIG"
def outputDir = new File(config.outputDir ?: System.getProperty('user.home'))
def versionLimit = config.versionLimit ?: -1

if (versionLimit == 0)
{
    cli.usage()
    System.exit(2)
}

//******************************************************************************
//* Retrieves the Rexx execute that user wants to run, if it is not specified
//* then tries to determine the next Rexx execute to run based on the
//* available output files. 
//******************************************************************************

def targetDir = "$outputDir/sclmMigration/${proj.toLowerCase()}"
def rexxExecs = [EXTMTDT:['members.xml','langext.txt','projseq.txt','archtype.txt','keyref.xml'], GENDEF:['systemDefinition.xml','fileMetaData.xml'], EXTSRC:['members.txt']]
def rexxExec
def rexxExecName = parameters.x
if (rexxExecName)
{
    rexxExec = rexxExecs.find { exec, outputs ->
        exec == rexxExecName
    }    
}
else
{
    rexxExec = rexxExecs.find { exec, outputs ->
        def outputNotExist = outputs.findAll { output -> !new File("$targetDir/$output").exists() }
        if (outputNotExist.size())
        {
            //println "Execute $exec because expected output files '${outputNotExist.join(',')}' are not found"
            true
        }
        else false
    }
}

if (!rexxExec)
{
    println "Either user does not specify a Rexx step to execute or all SCLM information has been extracted"
    System.exit(0)
}

println "Executes ${rexxExec.key}"

//******************************************************************************
//* Delete all previous generated output files
//******************************************************************************
if (outputDir.exists())
{
    println "Deletes any existing output files in directory $targetDir"
    rexxExec.value.each { output ->        
        new File("$targetDir/$output").delete()        
    }
}
else
{
    outputDir.mkdirs()
}

//******************************************************************************
//* Create a temporary data set and copy the Rexx execute and sclmmig.config
//* to this data set
//******************************************************************************
def rexxDataset = "${tempHlq}.REXX"
println "Creates data set '$rexxDataset'"
new CreatePDS().dataset(rexxDataset).options('CYL SPACE(1,5) LRECL(80) RECFM(F,B) BLKSIZE(32720) DSORG(PO) DSNTYPE(LIBRARY)').execute()

def rexxFileLocation = scriptLocation.resolve("../rexx").toFile()
def member = rexxExec.key
def rexxFile = new File(rexxFileLocation, "${member}.rexx")
println "Copies file $rexxFile to $rexxDataset($member)"
new CopyToPDS().file(rexxFile).dataset(rexxDataset).member(member).execute()

println "Copies $configFile to $rexxDataset(MIGCFG)"
new CopyToPDS().file(configFile).dataset(rexxDataset).member('MIGCFG').execute()

//******************************************************************************
//* Execute the REXX exec
//******************************************************************************
println "Executes $rexxDataset($member)"
def logDir = new File("$targetDir/logs")
logDir.exists()?:logDir.mkdirs()
def logFile = new File(logDir, "${member}.log")
def step = new ISPFExec().confDir(dbbConf).logFile(logFile).logEncoding('Cp1047').keepCommandScript(false)
step.command("EX '${rexxDataset}($member)'")
step.addDDStatment("CMDSCP", "${tempHlq}.ISPFGWY.EXEC", "TRACKS SPACE(1,1) LRECL(270) RECFM(F,B) DSORG(PS)", false)
def rc = step.execute()

//******************************************************************************
//* Display the result
//******************************************************************************
if (rc)
{
    println "Failed to run $rexxDataset($member), rc = $rc.  See $logFile for more details"
    System.exit(1)
}

println "Successfully executed $rexxDataset($member)"
println "The following files were generated in directory $targetDir:"
rexxExec.value.each { output ->
    println "   $output"
}
//println "You can force to re-run this step ${rexxExec.key} by deleting any of the above output files"     

System.exit(0)        





