package sparkgis.data;
/* Java imports */
import java.io.Serializable;
/* Spark imports */
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
/* JTS imports */
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTReader;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.io.ParseException;


/**
 * Contains all data components required for querying. Populated and returned by SparkPrepareData
 * T Input spatial data type (SpatialObject OR byte[])
 */
public class DataConfig<T> implements Serializable
{
    private final String dataID;
    private JavaRDD<T> originalData;
    /* 
     * geometry index in TAB separated string 
     * by default 1 (polygonID, polygonGeom)
     */
    private int geomid = 2;
    
    public Space space;
    
    public DataConfig(String dataID, JavaRDD<T> data){
	this.dataID = dataID;
	this.originalData = data;
	this.space = new Space();
    }

    /**
     * Abstract method to extract MBB from spatial data objects
     */
    // protected abstract JavaRDD<Tile> extractMBBs();
    // public abstract void setData(JavaRDD<T> data);
    // public abstract JavaRDD<T> getData();

    public JavaRDD<T> getData(){return originalData;}
    public void setData(JavaRDD<T> data){this.originalData = data;}
    
    
    public String getID(){return this.dataID;}
    
    public int getGeomid() {return this.geomid;}
    public void setGeomid(int geomid) {this.geomid = geomid;}

    /**
     * Preprocess spatial data for further spatial querying
     * (1) IO
     * (2) Extract MBBs
     * (3) Extract Space parameters
     */
    public void prepare(){
	Tile spaceDims = extractMBBs().reduce(new Function2<Tile, Tile, Tile>(){
		public Tile call (Tile t1, Tile t2){
		    Tile ret = new Tile();
		    ret.minX = (t1.minX < t2.minX) ? t1.minX : t2.minX;
		    ret.minY = (t1.minY < t2.minY) ? t1.minY : t2.minY;
		    ret.maxX = (t1.maxX > t2.maxX) ? t1.maxX : t2.maxX;
		    ret.maxY = (t1.maxY > t2.maxY) ? t1.maxY : t2.maxY;
		    ret.count = t1.count + t2.count;
		    return ret;
		}
	    });
	this.space.setMinX(spaceDims.minX);
	this.space.setMinY(spaceDims.minY);
	this.space.setMaxX(spaceDims.maxX);
	this.space.setMaxY(spaceDims.maxY);

	this.space.setSpaceObjects(spaceDims.count);
    }

    private JavaRDD<Tile> extractMBBs(){
	return originalData.map(new MBBExtractor())
	    .filter(new Function <Tile, Boolean>(){
		    public Boolean call(Tile t) {
			return !((t.minX+t.minY+t.maxX+t.maxY) == 0);
		    }
		});
    }

    /**
     * Minimum Bounding Box Extraction for binary spatial data
     */
    class MBBExtractor implements Function<T, Tile>{
	public Tile call(T s){
	    Tile ret = new Tile();
	    try{
		Geometry geometry = null;
		if (s instanceof SpatialObject){
		    WKTReader reader = new WKTReader();
		    geometry = reader.read(((SpatialObject)s).getSpatialData());
		}
		else if (s.getClass().isArray()){
		    WKBReader reader = new WKBReader();
		    geometry = reader.read((byte[])s);
		}
		else
		    throw new RuntimeException("[MBBExtractor] Invalid spatial object type");
		Envelope env = geometry.getEnvelopeInternal();
		
		if (env != null){
		    ret.minX = env.getMinX();
		    ret.minY = env.getMinY();
		    ret.maxX = env.getMaxX();
		    ret.maxY = env.getMaxY();
		}
	    }catch (ParseException e) {e.printStackTrace();}
	
	    return ret;
	}
    }
    
    /********************* Space Stuff *******************/
    

    /* Unecessary stuff */

    // private int blockSize = 67108864; // default block size = 64MB
    // private final Space dataSpace = new Space(); // corresponds to data.cfg in Hadoop-GIS
    // private List<Tile> partitionIDX;
    // public void setPartitionIDX(List<Tile> partIDX) {this.partitionIDX = partIDX;}

    //public List<Tile> getPartitionIDX() {return this.partitionIDX;}
    //public int getPartitionsCount() {return this.partitionIDX.size();}
    
    // /**
    //  * Calculate and return partition size from input size and ratio
    //  * NOTE: setSpaceObjects() must be called before calling this. Otherwise "divided by zero"
    //  * @param totalSize Input file size in bytes
    //  * @param ratio 
    //  */
    // public int getPartitionBucketSize(long totalSize, int ratio){
    // 	if (dataSpace.partitionBucketSize == -1){
    // 	    int partSize = 0;
    // 	    double avgObjSize = totalSize / dataSpace.numObjects;
    // 	    dataSpace.partitionBucketSize = (int) Math.floor((blockSize * ratio) / avgObjSize);
    // 	}
    // 	return dataSpace.partitionBucketSize;
    // }

    // public void setPartitionBucketSize(int partSize) {dataSpace.partitionBucketSize = partSize;}

    // public String getSpace(){
    // 	if (dataSpace != null)
    // 	    return dataSpace.toString();
    // 	else
    // 	    return "Configuration not set yet ...";
    // }
    
    //public int getPartitionBucketSize(){return dataSpace.partitionBucketSize;}

    // class Space implements Serializable
    // {
    // 	double dataMinX;
    // 	double dataMinY;
    // 	double dataMaxX;
    // 	double dataMaxY;
    // 	long numObjects = 0;
    // 	int partitionBucketSize = -1;
	
    // 	public String toString(){
    // 	    return "dataMinX=" + dataMinX + "\ndataMinY=" + dataMinY + "\ndataMaxX=" + dataMaxX + "\ndataMaxY=" + dataMaxY + "\nnumobjects=" + numObjects + "\npartitionsize=" + partitionBucketSize;
    // 	}
    // }
    

    /*** Can be used for debugging ***/
    // public String toString(){
    // 	String ret;
    // 	ret = "partfile.idx\n";
    // 	// if (partitionIDX != null){
    // 	//     for (Tile t : partitionIDX)
    // 	// 	ret = ret + t.toString() + "\n";
    // 	// }
    // 	ret = ret + "Geomid: " + geomid + "\n";
    // 	ret = ret + "SpanX: " + getSpanX() + "\n";
    // 	ret = ret + "SpanY: " + getSpanY() + "\n";
    // 	ret = ret + dataSpace.toString();
    // 	return ret;
    // }
}
