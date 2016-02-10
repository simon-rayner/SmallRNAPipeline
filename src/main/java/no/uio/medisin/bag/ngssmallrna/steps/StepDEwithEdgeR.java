/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package no.uio.medisin.bag.ngssmallrna.steps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Random;

import no.uio.medisin.bag.ngssmallrna.pipeline.MiRNAFeature;
import no.uio.medisin.bag.ngssmallrna.pipeline.MirBaseSet;
import no.uio.medisin.bag.ngssmallrna.pipeline.SampleDataEntry;
import org.apache.commons.lang3.StringUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 *   1. parse SAM file to extract and process the miRNA reads to determine isomiR content
 *   2. merge the counts from each sample to generate a single count file 
 *      that can be used for differential expression analysis
 * 
 *   Input is a SAM file
 * 
 * @author sr
 */

public class StepDEwithEdgeR extends NGSStep{
    
    static Logger                       logger                      = LogManager.getLogger();

    private              String         rScriptFilename             = "";
    
    
    private static final String         infileExtension             = "";
    private static final String         groupsFileExtension         = ".groups.tsv";
    private static final String         miRCountsExtension          = ".trim.clp.gen.mircounts.tsv";
    private static final String         deResultsExtension          = ".DE.edgeR.sort.csv";
    private static final String         deSampleCountsExtension     = ".DE.edgeR.cbs.csv";
    private static final String         deSummaryExtension          = ".DE.edgeR.summary.txt";
    private static final String         dePlotMDSExtension          = ".DE.edgeR.MDS.";
    private static final String         dePlotBCVExtension          = ".DE.edgeR.BCV.";
    private static final String         dePlotSmearExtension        = ".DE.edgeR.Smear.";
    
    
    private String                      mergedCountsFile;
    private String                      groupsFile;
    private String                      deResultsFile;
    private String                      deCountsBySampleFile;
    private String                      deSummaryFile;
    private String                      dePlotBCVfile;
    private String                      dePlotMDSfile;
    private String                      dePlotSmearfile;
    
    private static final int            width                       = 8;
    private static final int            height                      = 8;
    private static final String         units                       = "in";
    private static final int            resolution                  = 300;
    
    
    private MirBaseSet                  miRBaseMiRNAList            = new MirBaseSet();
    /**
     * 
     * @param sid StepInputData
     * 
     */
    public StepDEwithEdgeR(StepInputData sid){
        stepInputData = sid;
    }
    
    @Override
    public void execute(){
        this.setPaths();
        
        /*
            diffExpressionAnalysisParams.put("pvalue", this.getDiffExpressionPVal());
            diffExpressionAnalysisParams.put("host", this.getBowtieMappingReferenceGenome());
            diffExpressionAnalysisParams.put("miRBaseHostGFFFile", this.getMiRBaseHostGFF());
        */
        try{
            stepInputData.verifyInputData();            
        }
        catch(IOException exIO){
            logger.info("exception parsing InputData" + exIO);
        }
    
        
        
        /*
            1. read in all sample count files and merge
            2. output merged count file
            3. generate R script to perform DE using EdgeR
            4. process EdgeR output file 
        */
        try{
            miRBaseMiRNAList.loadMiRBaseData((String) stepInputData.getStepParams().get("host"), (String) stepInputData.getStepParams().get("miRBaseHostGFFFile"));
        }
        catch(IOException exIO){
            logger.error("error reading miRBase reference file <" + (String) stepInputData.getStepParams().get("miRBaseHostGFFFile") + ">\n" + exIO.toString());
        }
        
        
        Boolean fA = new File(outFolder).mkdir();       
        if (fA) logger.info("created output folder <" + outFolder + "> for results" );
        
        
        logger.info("Merging Count Files");
        String headerLine = "name";
        String[] countStrings = new String[miRBaseMiRNAList.getNumberOfEntries()];
        Arrays.fill(countStrings, "");
        
        Iterator itSD = this.stepInputData.getSampleData().iterator();
        while (itSD.hasNext()){
            SampleDataEntry sampleData = (SampleDataEntry)itSD.next();
            headerLine = headerLine.concat("\t" + sampleData.getFastqFile1().replace(".fastq", ""));
            String  miRCountsFile  = inFolder + FILESEPARATOR + sampleData.getFastqFile1().replace(".fastq", miRCountsExtension);
            miRCountsFile = miRCountsFile.replace(FILESEPARATOR + FILESEPARATOR, FILESEPARATOR).trim();
            try{
                int m=0;
                BufferedReader brmiRCounts  = new BufferedReader(new FileReader(new File(miRCountsFile)));
                    String countLine = "";
                    while((countLine = brmiRCounts.readLine()) != null){
                        countStrings[m] = countStrings[m].concat("\t" + countLine.split("\t")[1].trim() );
                        m++;
                    }
                brmiRCounts.close();
            }
            catch(IOException ex){
                logger.error("error reading count files for merging <" + miRCountsFile + "> \n" + ex.toString());
            }
        }
        
        logger.info("Writing merged count files");
        mergedCountsFile        = outFolder + FILESEPARATOR + stepInputData.getProjectID() + ".merged.mirna_counts.tsv"; 
        
        try{
            BufferedWriter bwMC = new BufferedWriter(new FileWriter(new File(mergedCountsFile)));
            
            bwMC.write(headerLine + "\n");
            int m=0;
            for(MiRNAFeature miR: miRBaseMiRNAList.getMiRBaseMiRNAList()){
                bwMC.write(miR.getMimatID() + "|" + miR.getName() + countStrings[m] + "\n");
                m++;
            }
            
            bwMC.close();
        }
        catch(IOException exIO){
            logger.info("error writing merged counts File <" + mergedCountsFile + ">\n" + exIO);        
        }
        generateGroupsFile();
        buildRScript();
        executeRScript();
        
    }
    
    
    
    /**
     * 
     * generates the a file that contains all the grouping information for all
     * samples in the experiment.
     * 
     * Grouping is according to the Condition column in the data file

          this has the format:
            File               Source	Condition   Time	Note
            SRR1642941.fastq	P1	U           NA	(U|44|CR|M|IF)
            SRR1642942.fastq	P1	T           NA	(T|44|CR|M|IF)
            SRR1642943.fastq	P2	U           NA	(U|52|NC|M|IF)
            SRR1642944.fastq	P2	T           NA	(T|51|NC|M|IF)
        
            i.e., Condition is equivalent to Group
        
            Need the file in the format
            Group	U   T   U   T
            sample names    SRR1642941  SRR1642942  SRR1642943  SRR1642944
            
            We could write the R to parse the sample file, but it makes the code
            harder to understand

     * 
     */
    private void generateGroupsFile(){
        

        String groupString = "Group";
        String sampleString = "sample names";

        Iterator itSD = this.stepInputData.getSampleData().iterator();
        while (itSD.hasNext()){
            SampleDataEntry sampleData = (SampleDataEntry)itSD.next();
            groupString = groupString.concat("\t" + sampleData.getCondition());
            sampleString = sampleString.concat("\t" + sampleData.getFastqFile1().replace(".fastq", ""));
        }        
        
        groupsFile = outFolder + FILESEPARATOR + stepInputData.getProjectID() + groupsFileExtension;
        logger.info("writing groups file "  + groupsFile);
        try{
            BufferedWriter bwGF = new BufferedWriter(new FileWriter(new File(groupsFile)));    
                bwGF.write(groupString + "\n");
                bwGF.write(sampleString + "\n");
            bwGF.close();
        }
        catch(IOException exGF){
            logger.error("error writing groups file "  + groupsFile);
            logger.error(exGF);
        }

                
    }
    
    
    
    
    
    /**
     * construct the R script to perform DE analysis for this dataset
     * Need to create a new R script for each analysis and give it a
     * unique name so we can go back and check what parameters were
     * used in the analysis.
     * 
     * need to generate the input files based on the specified groups
     * 
     */
    private void buildRScript(){


        BigInteger big = new BigInteger(130, new Random());
        String randomName = new BigInteger(130, new SecureRandom()).toString(32);
        rScriptFilename = outFolder + FILESEPARATOR + randomName + ".R";
        rScriptFilename = rScriptFilename.replace(FILESEPARATOR + FILESEPARATOR, FILESEPARATOR);
        
        deResultsFile           = outFolder + FILESEPARATOR + stepInputData.getProjectID() + deResultsExtension;    
        deCountsBySampleFile    = outFolder + FILESEPARATOR + stepInputData.getProjectID() + deSampleCountsExtension;
        deSummaryFile           = outFolder + FILESEPARATOR + stepInputData.getProjectID() + deSummaryExtension;
        
        int minCounts = 10;
        ArrayList<String> cmdSet = new ArrayList<>();

        cmdSet.add("#");
        cmdSet.add("#   Rscript generated for project " + this.stepInputData.getProjectID());
        cmdSet.add("#   created: " + new Date());
        cmdSet.add("#");
        cmdSet.add("#");
        cmdSet.add("library(edgeR)");
        cmdSet.add("RawCounts<-read.delim(\"" + mergedCountsFile + "\", row.names=\"name\")");
        cmdSet.add("g<-read.csv(\"" + groupsFile + "\", header=FALSE, sep=\"\\t\", row.names=1)");
        cmdSet.add("sampleGroups <- unname(unlist(g[\"Group\",]))");
        cmdSet.add("CountsDGE <- DGEList(counts=RawCounts, group=sampleGroups)");
        cmdSet.add("");
        cmdSet.add("CountsAboveNoise <- rowSums(cpm(CountsDGE)>" + minCounts + ") >= 2");
        cmdSet.add("CountsLessNoiseDGE <- CountsDGE[CountsAboveNoise,]");
        cmdSet.add("CountsLessNoiseDGE$samples$lib.size <- colSums(CountsLessNoiseDGE$counts)");
        cmdSet.add("");
        cmdSet.add("NormFactors <- calcNormFactors(CountsLessNoiseDGE)");
        cmdSet.add("");
        cmdSet.add("CommonDispersion <- estimateCommonDisp(NormFactors, verbose=TRUE)");
        cmdSet.add("TagwiseDispersion <- estimateTagwiseDisp(CommonDispersion, trend=\"none\")");
        cmdSet.add("");
        cmdSet.add("ExactTestTagDisp <- exactTest(TagwiseDispersion)");
        cmdSet.add("");
        cmdSet.add("tTags <- topTags(ExactTestTagDisp, n=Inf)");
        cmdSet.add("");
        cmdSet.add("");
        cmdSet.add("resultFile<-\"" + deResultsFile + "\"");
        cmdSet.add("write.table(tTags[tTags$table$PValue<=" + this.stepInputData.getStepParams().get("pvalue") + ", ], file=\"" + deResultsFile + "\", " + " sep=\",\", row.names=TRUE)");
        
        cmdSet.add("deTags<-rownames(tTags[tTags$table$PValue<=" + this.stepInputData.getStepParams().get("pvalue") + ", ])");
        cmdSet.add("");
        cmdSet.add("write.table(cpm(TagwiseDispersion)[deTags,], file=\"" + deCountsBySampleFile + "\", " + " sep=\",\", row.names=TRUE)");
        cmdSet.add("");
        cmdSet.add("write.table(summary(decideTestsDGE(ExactTestTagDisp, p=" + this.stepInputData.getStepParams().get("pvalue") + ", adjust=\"BH\")), file=\"" + deSummaryFile + "\", " + " sep=\",\")");
        cmdSet.add("");
        
        
        dePlotBCVfile           = outFolder + FILESEPARATOR + stepInputData.getProjectID() + dePlotBCVExtension + "png";
        cmdSet.add("png(\"" + dePlotBCVfile + "\", width=" + width + ", height=" + height + ", units=\"" + units + "\", res=" + resolution + ")");
        cmdSet.add("plotBCV(TagwiseDispersion)");
        cmdSet.add("dev.off()");
        cmdSet.add("");
        
        dePlotMDSfile           = outFolder + FILESEPARATOR + stepInputData.getProjectID() + dePlotMDSExtension + "png";
        cmdSet.add("png(\"" + dePlotMDSfile + "\", width=" + width + ", height=" + height + ", units=\"" + units + "\", res=" + resolution + ")");
        cmdSet.add("plotMDS(TagwiseDispersion)");
        cmdSet.add("dev.off()");
        cmdSet.add("");

        dePlotSmearfile         = outFolder + FILESEPARATOR + stepInputData.getProjectID() + dePlotSmearExtension + "png";
        cmdSet.add("png(\"" + dePlotSmearfile + "\", width=" + width + ", height=" + height + ", units=\"" + units + "\", res=" + resolution + ")");
        cmdSet.add("plotSmear(TagwiseDispersion)");
        cmdSet.add("dev.off()");        
        cmdSet.add("");
        
        try{
            BufferedWriter bwRC = new BufferedWriter(new FileWriter(new File(rScriptFilename)));
                for(String cmd: cmdSet){
                    bwRC.write(cmd + "\n");
                }
            bwRC.close();
        }
        catch(IOException exIO){
            logger.error("error writing generated RScript to file <" + rScriptFilename + ">");
            logger.error(exIO);
        }
    }
    
    
    
    private void executeRScript(){
        

        String pathToData = stepInputData.getProjectRoot() + FILESEPARATOR + stepInputData.getProjectID();
        ArrayList<String> cmd = new ArrayList<>();

        cmd.add("/usr/local/bin/Rscript");
        cmd.add(rScriptFilename);


        String cmdRunRScript = StringUtils.join(cmd, " ");
        cmdRunRScript = cmdRunRScript.replace(FILESEPARATOR + FILESEPARATOR, FILESEPARATOR);
        logger.info("Rscript command:\t" + cmdRunRScript);

        try{
            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(cmdRunRScript);
            BufferedReader brAStdin  = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            BufferedReader brAStdErr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

                String line = null;
                logger.info("<OUTPUT>");
                while ((line = brAStdin.readLine()) != null)
                    logger.info(line);
                logger.info("</OUTPUT>");

                logger.info("<ERROR>");
                while ( (line = brAStdErr.readLine()) != null){
                    logger.info(line);
                }
                // need to parse the output from Bowtie to get the mapping summary
                logger.info("</ERROR>");

                int exitVal = proc.waitFor();            
                logger.info("Process exitValue: " + exitVal);   

            brAStdin.close();
            brAStdErr.close();        
        }
        catch(IOException exIO){
            logger.info("error executing RScript command\n" + cmdRunRScript);
            logger.info(exIO);
        }
        catch(InterruptedException exIE){
            logger.info("error executing RScript command\n" + exIE);            
        }
    }
    
    
    /**
     * Verify Input Data for parsing SAM file for miRNAs
     * 
     */        
    @Override
    public void verifyInputData(){
        Iterator itSD = this.stepInputData.getSampleData().iterator();
        while (itSD.hasNext()){
            SampleDataEntry sampleData = (SampleDataEntry)itSD.next();
            /*
            if (sampleData.getDataFile().toUpperCase().endsWith(infileExtension.toUpperCase())==false)
            {
                throw new IllegalArgumentException("AdapterTrimming: incorrect file extension for input file <" 
                  + sampleData.getDataFile() + ">. " 
                  + "should have <" + infileExtension + "> as extension");
            }
            
            if (sampleData.getDataFile().toUpperCase().endsWith(outfileExtension.toUpperCase())==true)
            {
                logger.warn("AdapterTrimming: input file has output file extension (.trim.fastq)");
                logger.warn("this file has already been trimmed");
            }
            */
            
        }
            // does input file have correct extension?
        // does input file have the same extension as expected for the output file?
    }
    
    
    
    
    @Override
    public void verifyOutputData(){
        
    }
}