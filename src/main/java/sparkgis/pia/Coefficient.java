package sparkgis.pia;
/* Java imports */
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.io.Serializable;
/* Spark imports */
import scala.Tuple2;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.Optional;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.PairFlatMapFunction;
/* Local imports */
import sparkgis.data.Tile;
import sparkgis.enums.HMType;
import sparkgis.data.TileStats;
import sparkgis.coordinator.SparkGISContext;

public class Coefficient implements Serializable{

    private static final int numPartitions = 100;

    public static JavaRDD<TileStats> execute(
					     JavaRDD<Tuple2<Integer, Iterable<String>>> data,
					     List<Tile> partfile,
					     final HMType hmType
					     ){

    	// make variable final to pass to inner class
    	final int index = hmType.value;
    	// map data to Tuple <tileID,Jaccard Coefficient>
    	JavaPairRDD<Integer, Double> pairs =
    	    data.flatMapToPair(new PairFlatMapFunction<Tuple2<Integer, Iterable<String>>, Integer, Double>(){
    		    public Iterator<Tuple2<Integer, Double>> call (Tuple2<Integer, Iterable<String>> t){
			List<Tuple2<Integer, Double>> ret = new ArrayList<Tuple2<Integer, Double>>();
			final int tileId = t._1();
			for (String s : t._2()){
			    String[] fields = s.split("\t");
			    int len = fields.length;
			    Tuple2<Integer, Double> t2 =
				new Tuple2<Integer, Double>(
							    //Integer.parseInt(fields[len-1].trim()),
							    tileId,
							    Double.parseDouble(fields[len-index].trim())
							    );
				ret.add(t2);
			}
			return ret.iterator();
		    }
    	    });

    	/** Helper functions for average calculation used by combineByKey **/
    	Function<Double, Tuple2<Double, Integer>> f1 =
    	    new Function<Double, Tuple2<Double, Integer>>(){
    	    public Tuple2<Double, Integer> call(Double a){
    		return new Tuple2<Double, Integer>(a, 1);
    	    }
    	};

    	Function2<Tuple2<Double, Integer>, Double, Tuple2<Double, Integer>> addAndCount =
    	    new Function2<Tuple2<Double, Integer>, Double, Tuple2<Double, Integer>>(){
    	    public Tuple2<Double, Integer> call (Tuple2<Double, Integer> a, Double b){
    		// sum = a._1()+b, count = a._2()+1
    		return new Tuple2<Double, Integer>(a._1()+b, a._2()+1);
    	    }
    	};

    	Function2<Tuple2<Double, Integer>, Tuple2<Double, Integer>, Tuple2<Double, Integer>> reduce = new Function2<Tuple2<Double, Integer>, Tuple2<Double, Integer>, Tuple2<Double, Integer>>(){
    	    public Tuple2<Double, Integer> call (Tuple2<Double, Integer> a, Tuple2<Double, Integer> b){
    		// combine sum and count for each key
    		return new Tuple2<Double, Integer>(a._1()+b._1(), a._2()+b._2());
    	    }
    	};
    	/************************* END HELPER FUNCTIONS ****************/



    	// calculate averages using above functions
    	// Format: Key, Tuple2<Sum, Count>
    	JavaPairRDD<Integer, Tuple2<Double, Integer>> sumCounts =
    	    pairs.combineByKey(f1, addAndCount, reduce)/*, numPartitions)*/;

    	// calculate average for each key
    	// Format: TileID, jaccard-coeffient-average
    	JavaPairRDD<Integer, Double> avgByKey =
    	    sumCounts.mapValues(
    				new Function<Tuple2<Double, Integer>, Double>(){
    				    public Double call (Tuple2<Double, Integer> a){
    					// sum/count
    					return (a._1()/a._2());
    				    }
    				});
	return mapResultsToTile(partfile, avgByKey, hmType);
    }

    /**
     * @param partfile      Tile information, can be extracted from DataConfig
     * @param resultByTile  Results values ordered by tile-id to be mapped to approriate tile from
     *                      partfile. JavaPairRDD<TileID, ResultValue>
     *
     * @return Per tile stats i.e. Tile information from partfile with results
     */
    public static JavaRDD<TileStats> mapResultsToTile(
						      List<Tile> partfile,
						      JavaPairRDD<Integer, Double> resultByTile,
						      final HMType hmType
						      ){

	JavaRDD<Tile> partfileRDD = SparkGISContext.sparkContext.parallelize(partfile);

    	// Format:
    	JavaPairRDD<Integer, Tile> pRDDPairs =
    	    partfileRDD.mapToPair(new PairFunction<Tile, Integer, Tile>(){
    		    public Tuple2<Integer, Tile> call (Tile tile){
    			return new Tuple2<Integer, Tile>((int)tile.tileID, tile);
    		    }
    		});
    	// Format: TileID, partfile-tile, average-jaccard-coeffieint
    	// JavaPairRDD<Integer, Tuple2<Tile, Double>> joined = pRDDPairs.join(resultByTile);

	// JavaRDD<TileStats> tileStats =
	//     joined.mapValues(new Function<Tuple2<Tile, Double>, TileStats>(){
	// 	    public TileStats call (Tuple2<Tile, Double> a){
	// 		TileStats t = new TileStats();
	// 		t.tile = a._1();
	// 		t.statistics = a._2();
	// 		t.type = hmType.toString();
	// 		return t;
	// 	    }
	// 	}).values();

	/* Left Outer Join */
        JavaPairRDD<Integer, Tuple2<Tile, Optional<Double>>> joined =
            pRDDPairs.leftOuterJoin(resultByTile);

        JavaRDD<TileStats> tileStats =
            joined.mapValues(new Function<Tuple2<Tile, Optional<Double>>, TileStats>(){
                    public TileStats call (Tuple2<Tile, Optional<Double>> tuple){
                        TileStats t = new TileStats();
                        t.tile = tuple._1();
                        t.type = hmType.toString();
                        if (tuple._2().isPresent()){
                            t.statistics = tuple._2().get();
                        }
                        else{
                            t.statistics = 0.0;
                        }
                        return t;
                    }
                }).values();

	return tileStats.sortBy(new Function<TileStats, Double>(){
		private static final long serialVersionUID = 1L;

		public Double call (TileStats ts){
		    return ts.statistics;
		}
	    }, false, 1);
    }


    // /**
    //  * Calculate per tile stats using Broadcast Variable for Joining
    //  * @param partfile      Tile information, can be extracted from DataConfig
    //  * @param resultByTile  Results values ordered by tile-id to be mapped to approriate tile from
    //  *                      partfile. JavaPairRDD<TileID, ResultValue>
    //  *
    //  * @return Per tile stats i.e. Tile information from partfile with results
    //  */
    // public static JavaRDD<TileStats> mapResultsToTileBV(
    // 						      List<Tile> partfile,
    // 						      JavaPairRDD<Integer, Double> resultByTile,
    // 						      final HMType hmType
    // 						      ){

    // 	final Broadcast<List<Tile>> partfileBV = SparkGIS.sc.broadcast(partfile);

    // 	// Format:
    // 	// JavaPairRDD<Integer, Tile> pRDDPairs =
    // 	//     partfileRDD.mapToPair(new PairFunction<Tile, Integer, Tile>(){
    // 	// 	    public Tuple2<Integer, Tile> call (Tile tile){
    // 	// 		return new Tuple2<Integer, Tile>((int)tile.tileID, tile);
    // 	// 	    }
    // 	// 	});

    // 	resultByTile.mapToPair(new PairFunction<Tile, Integer, Tile>(){
    // 		public Tuple2<Integer, Tile> call (Tile tile){
    // 		    return new Tuple2<Integer, Tile>((int)tile.tileID, tile);
    // 		}
    // 	    });

    // 	// Format: TileID, partfile-tile, average-jaccard-coeffieint
    // 	JavaPairRDD<Integer, Tuple2<Tile, Double>> joined = pRDDPairs.join(resultByTile);

    // 	JavaRDD<TileStats> tileStats =
    // 	    joined.mapValues(new Function<Tuple2<Tile, Double>, TileStats>(){
    // 		    public TileStats call (Tuple2<Tile, Double> a){
    // 			TileStats t = new TileStats();
    // 			t.tile = a._1();
    // 			t.statistics = a._2();
    // 			t.type = hmType.toString();
    // 			return t;
    // 		    }
    // 		}).values();

    // 	return tileStats.sortBy(new Function<TileStats, Double>(){
    // 		private static final long serialVersionUID = 1L;

    // 		public Double call (TileStats ts){
    // 		    return ts.statistics;
    // 		}
    // 	    }, false, 1);
    // }
}
