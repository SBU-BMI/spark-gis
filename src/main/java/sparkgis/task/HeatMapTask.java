/**
 * PATHOLOGY IMAGE ANALYTICS PLUGIN
 */
package sparkgis.task;
import java.io.*;
import java.io.PrintWriter;
import java.io.IOException;
 import java.io.File; 
// /* Java imports */
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
/* Spark imports */
import org.apache.spark.api.java.JavaRDD;
/* Local imports */
import sparkgis.pia.HMType;
import sparkgis.SparkGISConfig;
import sparkgis.core.data.Polygon;
import sparkgis.core.data.TileStats;
import sparkgis.core.io.ISparkGISIO;
import sparkgis.core.data.DataConfig;
import sparkgis.core.enums.Predicate;
import sparkgis.core.executionlayer.SparkPrepareData;
import sparkgis.pia.SparkSpatialJoinHM;

public class HeatMapTask extends Task implements Callable<String>{
    private final List<String> algos;
    private final String caseID;
    private final Predicate predicate;
    private final HMType type;
    private final String result_analysis_exe_id;
    
    public HeatMapTask(ISparkGISIO inputSrc, String caseID, List<String> algos, Predicate predicate, HMType hmType, ISparkGISIO outDest, String result_analysis_exe_id){
	super(inputSrc, outDest, algos.size());
	// pia spaecific varaibles
	this.caseID = caseID;
	this.algos = algos;
	this.predicate = predicate;
	this.type = hmType;
	this.result_analysis_exe_id = result_analysis_exe_id;
    }

    public void setPartitionSize(int pSize){super.pSize = pSize;}
   
    /**
     * Each HeatMapTask consists of 2 steps
     *   1. Generate configurations for algorithm pairs of input data (parallel)
     *   2. Generate heatmap from configurations
     */
    @Override
    public String call(){
	List<JavaRDD<TileStats>> results = new ArrayList<JavaRDD<TileStats>>();
	
	DataConfig[] configs = new DataConfig[super.batchFactor];
	List<Future<DataConfig>> futures = new ArrayList<Future<DataConfig>>();
	for (int i=0; i<super.batchFactor; ++i){
	    LinkedHashMap<String, Object> params = new LinkedHashMap<String, Object>();
	    /* HDFS params */
	    //params.put("geomIndex", "1");
	    //params.put("delimiter", "\t");
	    //params.put("dataDir", SparkGISConfig.hdfsAlgoData);
	    /* MongoDB params */
         
           params.put("host", SparkGISConfig.mongoHost);
    params.put("port", String.valueOf(SparkGISConfig.mongoPort));
         
	    params.put("db", SparkGISConfig.mongoDB);
	    params.put("collection", "results");
	    /* Common params */
	    params.put("analysis_execution_id", algos.get(i));
	    params.put("image.caseid", caseID);
	    futures.add(super.exeService.submit(new AsyncPrepareData(params)));
	}
	try{
	    for (int i=0; i<super.batchFactor; ++i)
		configs[i] = futures.get(i).get();
	}catch(Exception e){e.printStackTrace();
 
  
    }
	// close thread pool
	exeService.shutdown();

	final List<Integer> pairs = generatePairs();
	for (int i=0; i<pairs.size(); i+=2){
	    /* Step-2: Generate heatmap from configurations */
	    if ((configs[i] != null) && (configs[i+1] != null)){
		// generate heatmap based from algo1 and algo2 data configurations
		results.add(generateHeatMap(configs[i], configs[i+1]));
		sparkgis.stats.Profile.printProgress();
	    }
	    else System.out.println("Unexpected data configurations for caseID: " + caseID);
	}
	
	System.out.println("Done ...");
	for (JavaRDD<TileStats> result:results)
	    System.out.println("Count: "+ result.count());
	return "";
	
	// // heatmap stats generated for all algorithm pairs
	// // parameters to upload results to mongoDB
	// String caseID = configs[0].caseID;
	// String orig_analysis_exe_id = algos.get(0);
	// String title = "Heatmap - ";
	// for (String algo:algos)
	//     title = title + algo + ":";
	// // remove last ':' from tile
	// title = title.substring(0, title.length()-1);
	// String ret = "";
	// for (JavaRDD<TileStats> result:results)
	//     ret = outDest.writeTileStats(result, caseID, orig_analysis_exe_id, title, result_analysis_exe_id);
	// return ret;
    }

    // /**
    //  * Stage-1: Inner class to get data from Input source and generate data configuration
    //  */
    // public class AsyncPrepareData implements Callable<DataConfig>{
    //     private final String caseID;
    // 	private final String algo;	
    // 	public AsyncPrepareData(String caseID, String algo){
    // 	    this.caseID = caseID;
    // 	    this.algo = algo;    	    
    // 	}
	
    // 	@Override
    // 	public DataConfig call(){
    // 	    long start = System.nanoTime();
    // 	    // get data from input source and keep in memory
    // 	    JavaRDD<Polygon> polygonsRDD = inputSrc.getPolygonsRDD(caseID, algo).cache();
    // 	    long objCount = polygonsRDD.count();	
	    
    // 	    //Profile.log("[Obj]" + super.data + "-" + algo, objCount);
    // 	    start = System.nanoTime();
    // 	    if (objCount != 0){
    // 		// Invoke spark job: Prepare Data
    // 		SparkPrepareData job = new SparkPrepareData(caseID);
    // 		DataConfig ret = job.execute(polygonsRDD);
    // 		return ret;
    // 	    }
    // 	    return null;
    // 	}
    // }
    
    /**
     * Stage-2: Generate heatmap from data configurations
     */
    private JavaRDD<TileStats> generateHeatMap(DataConfig config1, DataConfig config2){
	long start = System.nanoTime();
	SparkSpatialJoinHM heatmap1 = new SparkSpatialJoinHM(config1, config2, predicate, super.pSize);
	return heatmap1.execute(type);
    }

    private List<Integer> generatePairs(){
	ArrayList<Integer> ret = new ArrayList<Integer>();
	for (int i=0; i<super.batchFactor; ++i){
	    for (int j=(i+1); j<super.batchFactor; ++j){
		ret.add(i);
		ret.add(j);
	    }
	}
	return ret;
    }
}
