package org.geotools.imageio.netcdf;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BandedSampleModel;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import javax.measure.unit.Unit;
import org.geotools.coverage.Category;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.DefaultDimensionDescriptor;
import org.geotools.coverage.grid.io.DimensionDescriptor;
import org.geotools.coverage.io.CoverageSource.AdditionalDomain;
import org.geotools.coverage.io.CoverageSource.DomainType;
import org.geotools.coverage.io.CoverageSource.SpatialDomain;
import org.geotools.coverage.io.CoverageSource.TemporalDomain;
import org.geotools.coverage.io.CoverageSource.VerticalDomain;
import org.geotools.coverage.io.CoverageSourceDescriptor;
import org.geotools.coverage.io.RasterLayout;
import org.geotools.coverage.io.catalog.CoverageSlice;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog;
import org.geotools.coverage.io.range.FieldType;
import org.geotools.coverage.io.range.RangeType;
import org.geotools.coverage.io.range.impl.DefaultFieldType;
import org.geotools.coverage.io.range.impl.DefaultRangeType;
import org.geotools.coverage.io.util.DateRangeComparator;
import org.geotools.coverage.io.util.DateRangeTreeSet;
import org.geotools.coverage.io.util.DoubleRangeTreeSet;
import org.geotools.coverage.io.util.NumberRangeComparator;
import org.geotools.data.DataUtilities;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.factory.GeoTools;
import org.geotools.feature.NameImpl;
import org.geotools.gce.imagemosaic.Utils;
import org.geotools.gce.imagemosaic.catalog.index.Indexer.Coverages.Coverage;
import org.geotools.gce.imagemosaic.catalog.index.SchemaType;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.imageio.netcdf.cv.CoordinateVariable;
import org.geotools.imageio.netcdf.utilities.NetCDFCRSUtilities;
import org.geotools.imageio.netcdf.utilities.NetCDFUtilities;
import org.geotools.referencing.operation.transform.ProjectiveTransform;
import org.geotools.resources.coverage.CoverageUtilities;
import org.geotools.resources.i18n.Vocabulary;
import org.geotools.resources.i18n.VocabularyKeys;
import org.geotools.util.DateRange;
import org.geotools.util.NumberRange;
import org.geotools.util.SimpleInternationalString;
import org.geotools.util.logging.Logging;
import org.opengis.coverage.SampleDimension;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.geometry.BoundingBox;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.metadata.spatial.PixelOrientation;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.TemporalCRS;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.util.InternationalString;
import org.opengis.util.ProgressListener;
import ucar.nc2.Dimension;
import ucar.nc2.Variable;
import ucar.nc2.constants.AxisType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateSystem;
import ucar.nc2.dataset.VariableDS;

/**
 * 
 * @author Simone Giannecchini, GeoSolutions SAS
 * @todo lazy initialization
 * @todo management of data read with proper mangling
 */
public class VariableAdapter extends CoverageSourceDescriptor {
  static class UnitCharReplacement {
    String from;

    String to;

    public UnitCharReplacement(String from, String to) {
      this.from = from;
      this.to = to;
    }

    String replace(String input) {
      if (input != null && input.contains(from)) {
        return input.replace(from, to);
      }
      return input;
    }
  }

  final static Set<UnitCharReplacement> UNIT_CHARS_REPLACEMENTS;

  static {
    UNIT_CHARS_REPLACEMENTS = new HashSet<UnitCharReplacement>();
    UNIT_CHARS_REPLACEMENTS.add(new UnitCharReplacement("-", "^-"));
    UNIT_CHARS_REPLACEMENTS.add(new UnitCharReplacement(".", "*"));
    UNIT_CHARS_REPLACEMENTS.add(new UnitCharReplacement("1/s", "s^-1"));
  }

  public class UnidataSpatialDomain extends SpatialDomain {
    /** The spatial coordinate reference system */
    private CoordinateReferenceSystem coordinateReferenceSystem;

    /** The spatial referenced envelope */
    private ReferencedEnvelope referencedEnvelope;

    /** The gridGeometry of the spatial domain */
    private GridGeometry2D gridGeometry;

    public ReferencedEnvelope getReferencedEnvelope() {
      return referencedEnvelope;
    }

    public void setReferencedEnvelope(ReferencedEnvelope referencedEnvelope) {
      this.referencedEnvelope = referencedEnvelope;
    }

    public GridGeometry2D getGridGeometry() {
      return gridGeometry;
    }

    public double[] getFullResolution() {
      AffineTransform gridToCRS = (AffineTransform) gridGeometry.getGridToCRS();
      return CoverageUtilities.getResolution(gridToCRS);
    }

    public void setGridGeometry(GridGeometry2D gridGeometry) {
      this.gridGeometry = gridGeometry;
    }

    public void setCoordinateReferenceSystem(CoordinateReferenceSystem coordinateReferenceSystem) {
      this.coordinateReferenceSystem = coordinateReferenceSystem;
    }

    @Override public Set<? extends BoundingBox> getSpatialElements(boolean overall, ProgressListener listener) throws IOException {
      return Collections.singleton(referencedEnvelope);
    }

    @Override public CoordinateReferenceSystem getCoordinateReferenceSystem2D() {
      return coordinateReferenceSystem;
    }

    @Override public MathTransform2D getGridToWorldTransform(ProgressListener listener) throws IOException {
      return gridGeometry.getGridToCRS2D(PixelOrientation.CENTER);
    }

    @Override public Set<? extends RasterLayout> getRasterElements(boolean overall, ProgressListener listener) throws IOException {
      Rectangle bounds = gridGeometry.getGridRange2D().getBounds();
      return Collections.singleton(new RasterLayout(bounds));
    }
  }

  public class UnidataTemporalDomain extends TemporalDomain {
    /**
         * @param adaptee
         */
    UnidataTemporalDomain(CoordinateVariable<?> adaptee) {
      if (!Date.class.isAssignableFrom(adaptee.getType())) {
        throw new IllegalArgumentException("Unable to wrap non temporal CoordinateVariable:" + adaptee.toString());
      }
      this.adaptee = (CoordinateVariable<Date>) adaptee;
    }

    final CoordinateVariable<Date> adaptee;

    public SortedSet<DateRange> getTemporalExtent() {
      Date startTime;
      try {
        startTime = adaptee.getMinimum();
        Date endTime = adaptee.getMaximum();
        final DateRange global = new DateRange(startTime, endTime);
        final SortedSet<DateRange> globalTemporalExtent = new DateRangeTreeSet();
        globalTemporalExtent.add(global);
        return globalTemporalExtent;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override public SortedSet<? extends DateRange> getTemporalElements(boolean overall, ProgressListener listener) throws IOException {
      if (overall) {
        final SortedSet<DateRange> extent = new TreeSet<DateRange>(new DateRangeComparator());
        for (Date dd : adaptee.read()) {
          extent.add(new DateRange(dd, dd));
        }
        return extent;
      } else {
        return getTemporalExtent();
      }
    }

    @Override public CoordinateReferenceSystem getCoordinateReferenceSystem() {
      return adaptee.getCoordinateReferenceSystem();
    }
  }

  public class UnidataVerticalDomain extends VerticalDomain {
    final CoordinateVariable<? extends Number> adaptee;

    /**
         * @param cv
         */
    UnidataVerticalDomain(CoordinateVariable<?> cv) {
      if (!Number.class.isAssignableFrom(cv.getType())) {
        throw new IllegalArgumentException("Unable to wrap a non Number CoordinateVariable:" + cv.toString());
      }
      this.adaptee = (CoordinateVariable<? extends Number>) cv;
    }

    public SortedSet<NumberRange<Double>> getVerticalExtent() {
      final CoordinateVariable<? extends Number> verticalDimension = this.adaptee;
      NumberRange<Double> global;
      try {
        global = NumberRange.create(verticalDimension.getMinimum().doubleValue(), verticalDimension.getMaximum().doubleValue());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      final SortedSet<NumberRange<Double>> globalVerticalExtent = new DoubleRangeTreeSet();
      globalVerticalExtent.add(global);
      return globalVerticalExtent;
    }

    @Override public SortedSet<? extends NumberRange<Double>> getVerticalElements(boolean overall, ProgressListener listener) throws IOException {
      if (overall) {
        final SortedSet<NumberRange<Double>> extent = new TreeSet<NumberRange<Double>>(new NumberRangeComparator());
        for (Number vv : adaptee.read()) {
          final double doubleValue = vv.doubleValue();
          extent.add(NumberRange.create(doubleValue, doubleValue));
        }
        return extent;
      } else {
        return getVerticalExtent();
      }
    }

    @Override public CoordinateReferenceSystem getCoordinateReferenceSystem() {
      return adaptee.getCoordinateReferenceSystem();
    }
  }

  public class UnidataAdditionalDomain extends AdditionalDomain {
    /** The detailed domain extent */
    private final Set<Object> domainExtent = new TreeSet<Object>();

    /** The merged domain extent */
    private final Set<Object> globalDomainExtent = new TreeSet<Object>(new Comparator<Object>() {
      private NumberRangeComparator numberRangeComparator = new NumberRangeComparator();

      private DateRangeComparator dateRangeComparator = new DateRangeComparator();

      public int compare(Object o1, Object o2) {
        boolean o1IsDateRange = true;
        boolean o2IsDateRange = true;
        if (o1 instanceof NumberRange) {
          o1IsDateRange = false;
        } else {
          if (!(o1 instanceof DateRange)) {
            throw new ClassCastException(o1.getClass() + " is not an known range type");
          }
        }
        if (o2 instanceof NumberRange) {
          o2IsDateRange = false;
        } else {
          if (!(o2 instanceof DateRange)) {
            throw new ClassCastException(o2.getClass() + " is not an known range type");
          }
        }
        if (o1IsDateRange && o2IsDateRange) {
          return dateRangeComparator.compare((DateRange) o1, (DateRange) o2);
        } else {
          if (!o1IsDateRange && !o2IsDateRange) {
            return numberRangeComparator.compare((NumberRange<?>) o1, (NumberRange<?>) o2);
          }
        }
        throw new ClassCastException("Incompatible range types: " + o1.getClass() + " is not the same as " + o2.getClass());
      }

      public boolean equals(Object o) {
        return false;
      }
    });

    /** The domain name */
    private final String name;

    private final DomainType type;

    final CoordinateVariable<?> adaptee;

    /**
         * @param domainExtent
         * @param globalDomainExtent
         * @param name
         * @param type
         * @param adaptee
         * TODO missing support for Range
         * TODO missing support for String domains
         * @throws IOException 
         */
    UnidataAdditionalDomain(CoordinateVariable<?> adaptee) throws IOException {
      this.adaptee = adaptee;
      name = adaptee.getName();
      Class<?> type = adaptee.getType();
      if (Date.class.isAssignableFrom(type)) {
        this.type = DomainType.DATE;
        globalDomainExtent.add(new DateRange((Date) adaptee.getMinimum(), (Date) adaptee.getMaximum()));
      } else {
        if (Number.class.isAssignableFrom(type)) {
          this.type = DomainType.NUMBER;
          globalDomainExtent.add(new NumberRange<Double>(Double.class, ((Number) adaptee.getMinimum()).doubleValue(), ((Number) adaptee.getMaximum()).doubleValue()));
        } else {
          throw new UnsupportedOperationException("Unsupported CoordinateVariable:" + adaptee.toString());
        }
      }
      domainExtent.addAll(adaptee.read());
    }

    @Override public Set<Object> getElements(boolean overall, ProgressListener listener) throws IOException {
      if (overall) {
        return globalDomainExtent;
      } else {
        return domainExtent;
      }
    }

    @Override public String getName() {
      return name;
    }

    @Override public DomainType getType() {
      return type;
    }

    public Set<Object> getDomainExtent() {
      return domainExtent;
    }
  }

  final VariableDS variableDS;

  private ucar.nc2.dataset.CoordinateSystem coordinateSystem;

  private NetCDFImageReader reader;

  private int numBands;

  private int rank;

  private SampleModel sampleModel;

  private int numberOfSlices;

  private int width;

  private int height;

  private CoordinateReferenceSystem coordinateReferenceSystem;

  private int[] shape;

  private Name coverageName;

  private SimpleFeatureType indexSchema;

  private final static java.util.logging.Logger LOGGER = Logging.getLogger(VariableAdapter.class);

  /** Usual schema are the_geom, imageIndex, so the first attribute (time or elevation) will have index = 2 */
  private static final int FIRST_ATTRIBUTE_INDEX = 2;

  /**
     * Extracts the compound {@link CoordinateReferenceSystem} from the unidata variable.
     * 
     * @return the compound {@link CoordinateReferenceSystem}.
     * @throws Exception
     */
  private void init() throws Exception {
    initSpatialElements();
    initRange();
    initSlicesInfo();
  }

  /**
     * @throws Exception 
     * 
     */
  private void initSlicesInfo() throws Exception {
    shape = variableDS.getShape();
    switch (shape.length) {
      case 2:
      numberOfSlices = 1;
      break;
      case 3:
      numberOfSlices = shape[0];
      break;
      case 4:
      numberOfSlices = 0 + shape[0] * shape[1];
      break;
      default:
      if (LOGGER.isLoggable(Level.WARNING)) {
        LOGGER.warning("Ignoring variable: " + getName() + " with shape length: " + shape.length);
      }
    }
  }

  /**
     * @throws IOException 
     * 
     */
  private void initSpatialElements() throws Exception {
    final List<DimensionDescriptor> dimensions = new ArrayList<DimensionDescriptor>();
    List<CoordinateVariable<?>> otherAxes = initCRS(dimensions);
    initSpatialDomain();
    addAdditionalDomain(otherAxes, dimensions);
    setDimensionDescriptors(dimensions);
    if (reader.ancillaryFileManager.isImposedSchema()) {
      updateDimensions(getDimensionDescriptors());
    }
  }

  /**
     * Update the dimensions to attributes mapping for this variable if needed.
     * Default behaviour is to get attributes from the name of the dimensions of the variable.
     * In case the indexer.xml contains an explicit schema with different attributes for time and elevation
     * we need to remap them and updates the dimensions mapping as well as the DimensionsDescriptors
     * @param dimensionDescriptors
     * @throws IOException
     */
  private void updateDimensions(List<DimensionDescriptor> dimensionDescriptors) throws IOException {
    final Map<Name, String> mapping = reader.ancillaryFileManager.variablesMap;
    final Set<Name> keys = mapping.keySet();
    final String varName = getName();
    for (Name key : keys) {
      final String origName = mapping.get(key);
      if (origName.equalsIgnoreCase(varName)) {
        final String coverageName = key.getLocalPart();
        final Coverage coverage = reader.ancillaryFileManager.coveragesMapping.get(coverageName);
        final SchemaType schema = coverage.getSchema();
        if (schema != null) {
          String schName = schema.getName();
          final CoverageSlicesCatalog catalog = reader.getCatalog();
          if (catalog != null) {
            SimpleFeatureType schemaType = null;
            try {
              if (schName != null) {
                schemaType = catalog.getSchema(schName);
              }
            } catch (IOException e) {
              schemaType = catalog.getSchema(coverageName);
            }
            if (schemaType != null) {
              updateMapping(schemaType, dimensionDescriptors);
              indexSchema = schemaType;
              break;
            }
            throw new IllegalStateException("Unable to find the table for this coverage: " + coverageName);
          }
        }
        break;
      }
    }
  }

  /**
     * Update the dimensionDescriptor attributes mapping by checking the actual attribute names from the schema
     * @param indexSchema
     * @param descriptors
     * @throws IOException
     */
  public void updateMapping(SimpleFeatureType indexSchema, List<DimensionDescriptor> descriptors) throws IOException {
    Map<String, String> dimensionsMapping = reader.georeferencing.getDimensions();
    Set<String> keys = dimensionsMapping.keySet();
    int indexAttribute = FIRST_ATTRIBUTE_INDEX;
    String currentDimName = NetCDFUtilities.TIME_DIM;
    if (keys.contains(currentDimName)) {
      if (remapAttribute(indexSchema, currentDimName, indexAttribute, descriptors, dimensionsMapping)) {
        indexAttribute++;
      }
    }
    currentDimName = NetCDFUtilities.ELEVATION_DIM;
    if (keys.contains(currentDimName)) {
      if (remapAttribute(indexSchema, currentDimName, indexAttribute, descriptors, dimensionsMapping)) {
        indexAttribute++;
      }
    }
  }

  /**
     * Remap an attribute for a specified dimension. Get it from the schemaType and update
     * both the related dimension Descriptor as well as the dimensions mapping.
     * 
     * @param indexSchema
     * @param currentDimName
     * @param indexAttribute
     * @param descriptors
     * @param dimensionsMapping
     * @return
     */
  private boolean remapAttribute(final SimpleFeatureType indexSchema, final String currentDimName, final int indexAttribute, final List<DimensionDescriptor> descriptors, Map<String, String> dimensionsMapping) {
    final int numAttributes = indexSchema.getAttributeCount();
    if (numAttributes <= indexAttribute) {
      return false;
    }
    final AttributeDescriptor attributeDescriptor = indexSchema.getDescriptor(indexAttribute);
    for (DimensionDescriptor descriptor : descriptors) {
      if (descriptor.getName().toUpperCase().equalsIgnoreCase(currentDimName)) {
        final String updatedAttribute = attributeDescriptor.getLocalName();
        if (!updatedAttribute.equals(((DefaultDimensionDescriptor) descriptor).getStartAttribute())) {
          ((DefaultDimensionDescriptor) descriptor).setStartAttribute(updatedAttribute);
          dimensionsMapping.put(currentDimName, updatedAttribute);
        }
        return true;
      }
    }
    return false;
  }

  /**
     * @param dimensions 
     * @return
     * @throws IllegalArgumentException
     * @throws RuntimeException
     * @throws IOException
     * @throws IllegalStateException
     */
  private List<CoordinateVariable<?>> initCRS(List<DimensionDescriptor> dimensions) throws IllegalArgumentException, RuntimeException, IOException, IllegalStateException {
    this.coordinateSystem = NetCDFCRSUtilities.getCoordinateSystem(variableDS);
    if (coordinateSystem == null) {
      throw new IllegalArgumentException("Provided CoordinateSystem is null");
    }
    coordinateSystem = new CoordinateSystemAdapter(coordinateSystem);
    final List<CoordinateVariable<?>> otherAxes = new ArrayList<CoordinateVariable<?>>();
    for (CoordinateAxis axis : coordinateSystem.getCoordinateAxes()) {
      CoordinateVariable<?> cv = reader.georeferencing.getCoordinateVariable(axis.getShortName());
      if (cv == null) {
        if (LOGGER.isLoggable(Level.FINE)) {
          LOGGER.fine("Unable to find a coordinate variable for " + axis.getFullName());
        }
        continue;
      }
      switch (cv.getAxisType()) {
        case Time:
        case RunTime:
        initTemporalDomain(cv, dimensions);
        continue;
        case GeoZ:
        case Height:
        case Pressure:
        String axisName = cv.getName();
        if (NetCDFCRSUtilities.VERTICAL_AXIS_NAMES.contains(axisName)) {
          initVerticalDomain(cv, dimensions);
        } else {
          otherAxes.add(cv);
        }
        continue;
        case GeoX:
        case GeoY:
        case Lat:
        case Lon:
        continue;
        default:
        otherAxes.add(cv);
      }
    }
    ReferencedEnvelope bbox = reader.georeferencing.getBoundingBox(variableDS.getShortName());
    coordinateReferenceSystem = bbox.getCoordinateReferenceSystem();
    return otherAxes;
  }

  /**
     * @param cv
     * @param dimensions 
     * @throws IOException 
     */
  private void initVerticalDomain(CoordinateVariable<?> cv, List<DimensionDescriptor> dimensions) throws IOException {
    this.setHasVerticalDomain(true);
    final UnidataVerticalDomain verticalDomain = new UnidataVerticalDomain(cv);
    this.setVerticalDomain(verticalDomain);
    dimensions.add(new DefaultDimensionDescriptor(Utils.ELEVATION_DOMAIN, cv.getUnit(), CoverageUtilities.UCUM.ELEVATION_UNITS.getSymbol(), cv.getName(), null));
  }

  /**
     * @param cv
     * @param dimensions 
     * @throws IOException 
     */
  private void initTemporalDomain(CoordinateVariable<?> cv, List<DimensionDescriptor> dimensions) throws IOException {
    if (!cv.getType().equals(Date.class)) {
      throw new IllegalArgumentException("Unable to init temporal domani from CoordinateVariable that does not bind to Date");
    }
    if (!(cv.getCoordinateReferenceSystem() instanceof TemporalCRS)) {
      throw new IllegalArgumentException("Unable to init temporal domani from CoordinateVariable that does not have a TemporalCRS");
    }
    this.setHasTemporalDomain(true);
    final UnidataTemporalDomain temporalDomain = new UnidataTemporalDomain(cv);
    this.setTemporalDomain(temporalDomain);
    dimensions.add(new DefaultDimensionDescriptor(Utils.TIME_DOMAIN, CoverageUtilities.UCUM.TIME_UNITS.getName(), CoverageUtilities.UCUM.TIME_UNITS.getSymbol(), cv.getName(), null));
  }

  /**
     * @param coordinateReferenceSystem
     * @throws MismatchedDimensionException
     * @throws IOException 
     */
  private void initSpatialDomain() throws Exception {
    final UnidataSpatialDomain spatialDomain = new UnidataSpatialDomain();
    this.setSpatialDomain(spatialDomain);
    ReferencedEnvelope bbox = reader.georeferencing.getBoundingBox(variableDS.getShortName());
    spatialDomain.setCoordinateReferenceSystem(coordinateReferenceSystem);
    spatialDomain.setReferencedEnvelope(bbox);
    spatialDomain.setGridGeometry(getGridGeometry());
  }

  /**
     * 
     */
  private void initRange() {
    rank = variableDS.getRank();
    width = variableDS.getDimension(rank - NetCDFUtilities.X_DIMENSION).getLength();
    height = variableDS.getDimension(rank - NetCDFUtilities.Y_DIMENSION).getLength();
    numBands = rank > 2 ? variableDS.getDimension(2).getLength() : 1;
    final int bufferType = NetCDFUtilities.getRawDataType(variableDS);
    sampleModel = new BandedSampleModel(bufferType, width, height, 1);
    final Number noData = NetCDFUtilities.getNodata(variableDS);
    Category[] categories = null;
    if (noData != null) {
      NumberRange noDataRange = NumberRange.create(noData.doubleValue(), true, noData.doubleValue(), true);
      categories = new Category[] { new Category(Vocabulary.formatInternational(VocabularyKeys.NODATA), new Color[] { new Color(0, 0, 0, 0) }, noDataRange) };
    }
    String description = variableDS.getDescription();
    if (description == null) {
      description = "";
    }
    final StringBuilder sb = new StringBuilder();
    final Set<SampleDimension> sampleDims = new HashSet<SampleDimension>();
    Unit unit = null;
    String unitString = variableDS.getUnitsString();
    if (unitString != null) {
      try {
        for (UnitCharReplacement replacement : UNIT_CHARS_REPLACEMENTS) {
          unitString = replacement.replace(unitString);
        }
        unit = Unit.valueOf(unitString);
      } catch (IllegalArgumentException iae) {
        if (LOGGER.isLoggable(Level.FINE)) {
          LOGGER.fine("Unable to parse the unit:" + unitString + "\nNo unit will be assigned");
        }
      }
    }
    sampleDims.add(new GridSampleDimension(description + ":sd", categories, unit));
    InternationalString desc = null;
    if (description != null && !description.isEmpty()) {
      desc = new SimpleInternationalString(description);
    }
    final FieldType fieldType = new DefaultFieldType(new NameImpl(getName()), desc, sampleDims);
    sb.append(!description.isEmpty() ? description.toString() + "," : description);
    final RangeType range = new DefaultRangeType(getName(), description, fieldType);
    this.setRangeType(range);
  }

  private void addAdditionalDomain(List<CoordinateVariable<?>> otherAxes, List<DimensionDescriptor> dimensions) {
    if (otherAxes == null || otherAxes.isEmpty()) {
      return;
    }
    final List<AdditionalDomain> additionalDomains = new ArrayList<AdditionalDomain>(otherAxes.size());
    this.setAdditionalDomains(additionalDomains);
    for (CoordinateVariable<?> cv : otherAxes) {
      UnidataAdditionalDomain domain;
      try {
        domain = new UnidataAdditionalDomain(cv);
        additionalDomains.add(domain);
        dimensions.add(new DefaultDimensionDescriptor(cv.getName(), cv.getUnit(), cv.getUnit(), cv.getName(), null));
        this.setHasAdditionalDomains(true);
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, e.getMessage(), e);
      }
    }
  }

  /**
     * Extracts the {@link GridGeometry2D grid geometry} from the unidata variable.
     * 
     * @return the {@link GridGeometry2D}.
     * @throws IOException 
     */
  protected GridGeometry2D getGridGeometry() throws IOException {
    int[] low = new int[2];
    int[] high = new int[2];
    double[] origin = new double[2];
    double scaleX = Double.POSITIVE_INFINITY, scaleY = Double.POSITIVE_INFINITY;
    for (CoordinateVariable<?> cv : reader.georeferencing.getCoordinatesVariables(variableDS.getShortName())) {
      if (!cv.isNumeric()) {
        continue;
      }
      final AxisType axisType = cv.getAxisType();
      switch (axisType) {
        case Lon:
        case GeoX:
        low[0] = 0;
        high[0] = (int) cv.getSize();
        if (cv.isRegular()) {
          origin[0] = cv.getStart();
          scaleX = cv.getIncrement();
        } else {
          final int valuesLength = (int) cv.getSize();
          double min = ((Number) cv.getMinimum()).doubleValue();
          double max = ((Number) cv.getMaximum()).doubleValue();
          if (!Double.isNaN(min) && !Double.isNaN(max)) {
            origin[0] = min;
            scaleX = (max - min) / valuesLength;
          } else {
            if (LOGGER.isLoggable(Level.FINE)) {
              LOGGER.log(Level.FINE, "Axis values contains NaN; finding first valid values");
            }
            for (int j = 0; j < valuesLength; j++) {
              double v = ((Number) cv.read(j)).doubleValue();
              if (!Double.isNaN(v)) {
                for (int k = valuesLength; k > j; k--) {
                  double vv = ((Number) cv.read(k)).doubleValue();
                  if (!Double.isNaN(vv)) {
                    origin[0] = v;
                    scaleX = (vv - v) / valuesLength;
                  }
                }
              }
            }
          }
        }
        break;
        case Lat:
        case GeoY:
        low[1] = 0;
        high[1] = (int) cv.getSize();
        if (cv.isRegular()) {
          if (cv.getIncrement() > 0) {
            scaleY = -cv.getIncrement();
            origin[1] = cv.getStart() - scaleY * (high[1] - 1);
          } else {
            scaleY = cv.getIncrement();
            origin[1] = cv.getStart();
          }
        } else {
          final int valuesLength = (int) cv.getSize();
          double min = ((Number) cv.getMinimum()).doubleValue();
          double max = ((Number) cv.getMaximum()).doubleValue();
          if (!Double.isNaN(min) && !Double.isNaN(max)) {
            scaleY = -(max - min) / valuesLength;
            origin[1] = max;
          } else {
            if (LOGGER.isLoggable(Level.FINE)) {
              LOGGER.log(Level.FINE, "Axis values contains NaN; finding first valid values");
            }
            for (int j = 0; j < valuesLength; j++) {
              double v = ((Number) cv.read(j)).doubleValue();
              if (!Double.isNaN(v)) {
                for (int k = valuesLength; k > j; k--) {
                  double vv = ((Number) cv.read(k)).doubleValue();
                  if (!Double.isNaN(vv)) {
                    origin[1] = v;
                    scaleY = -(vv - v) / valuesLength;
                  }
                }
              }
            }
          }
        }
        break;
        default:
        break;
      }
    }
    final AffineTransform at = new AffineTransform(scaleX, 0, 0, scaleY, origin[0], origin[1]);
    final GridEnvelope gridRange = new GridEnvelope2D(low[0], low[1], high[0] - low[0], high[1] - low[1]);
    final MathTransform raster2Model = ProjectiveTransform.create(at);
    return new GridGeometry2D(gridRange, PixelInCell.CELL_CENTER, raster2Model, coordinateReferenceSystem, GeoTools.getDefaultHints());
  }

  public int getNumBands() {
    return numBands;
  }

  /**
     * @return the number of dimensions in the variable.
     */
  public int getRank() {
    return rank;
  }

  public SampleModel getSampleModel() {
    return sampleModel;
  }

  public VariableAdapter(NetCDFImageReader reader, Name coverageName, VariableDS variable) throws Exception {
    this.variableDS = variable;
    this.reader = reader;
    this.coverageName = coverageName;
    setName(variable.getFullName());
    init();
  }

  @Override public UnidataSpatialDomain getSpatialDomain() {
    return (UnidataSpatialDomain) super.getSpatialDomain();
  }

  @Override public UnidataTemporalDomain getTemporalDomain() {
    return (UnidataTemporalDomain) super.getTemporalDomain();
  }

  @Override public UnidataVerticalDomain getVerticalDomain() {
    return (UnidataVerticalDomain) super.getVerticalDomain();
  }

  /**
     * @return
     */
  public int getWidth() {
    return width;
  }

  /**
     * @return
     */
  public int getHeight() {
    return height;
  }

  /**
     * Utility method to retrieve the z-index of a Variable coverageDescriptor stored on
     * {@link NetCDFImageReader} NetCDF Flat Reader {@link HashMap} indexMap.
     * 
     * @param imageIndex
     *                {@link int}
     * 
     * @return z-index {@link int} -1 if variable rank &lt; 3
     */
  public int getZIndex(int index) {
    if (rank > 2) {
      if (rank == 3) {
        return index;
      } else {
        if (rank == 4) {
          return index % NetCDFUtilities.getZDimensionLength(variableDS);
        } else {
          throw new IllegalStateException("Unable to handle more than 4 dimensions");
        }
      }
    }
    return -1;
  }

  /**
     * Utility method to retrieve the t-index of a Variable coverageDescriptor stored on
     * {@link NetCDFImageReader} NetCDF Flat Reader {@link HashMap} indexMap.
     * 
     * @param imageIndex
     *                {@link int}
     * 
     * @return t-index {@link int} -1 if variable rank > 4
     */
  public int getTIndex(int index) {
    if (rank > 2) {
      if (rank == 3) {
        return index;
      } else {
        return (int) Math.ceil(index / NetCDFUtilities.getZDimensionLength(variableDS));
      }
    }
    return -1;
  }

  /**
     * @return the numberOfSlices
     */
  public int getNumberOfSlices() {
    return numberOfSlices;
  }

  /**
     * @return the shape
     */
  public int[] getShape() {
    return shape;
  }

  /**
     * Return features for that variable adapter, starting from slices with index = "startIndex", and up to "limit" elements.
     * This allows for paging. Put the created features inside the provided collection
     * 
     * @param startIndex the first slice to be returned
     * @param limit the max number of features to be created
     * @param collection the feature collection where features need to be stored
     */
  public void getFeatures(final int startIndex, final int limit, final ListFeatureCollection collection) {
    final boolean hasVerticalAxis = coordinateSystem.hasVerticalAxis();
    final SimpleFeatureType indexSchema = collection.getSchema();
    final int bandDimension = rank - NetCDFUtilities.Z_DIMENSION;
    final int slicesNum = getNumberOfSlices();
    if (startIndex > slicesNum) {
      throw new IllegalArgumentException("The paging start index can\'t be higher than the number of available slices");
    }
    int lastIndex = startIndex + limit;
    if (lastIndex > slicesNum) {
      lastIndex = slicesNum;
    }
    final String varName = variableDS.getFullName();
    for (int imageIndex = startIndex; imageIndex < lastIndex; imageIndex++) {
      int zIndex = -1;
      int tIndex = -1;
      for (int i = 0; i < rank; i++) {
        switch (rank - i) {
          case NetCDFUtilities.X_DIMENSION:
          case NetCDFUtilities.Y_DIMENSION:
          break;
          default:
          {
            if (i == bandDimension && hasVerticalAxis) {
              zIndex = getZIndex(imageIndex);
            } else {
              tIndex = getTIndex(imageIndex);
            }
            break;
          }
        }
      }
      final Slice2DIndex variableIndex = new Slice2DIndex(tIndex, zIndex, varName);
      reader.ancillaryFileManager.addSlice(variableIndex);
      final SimpleFeature feature = createFeature(variableDS, coverageName.toString(), tIndex, zIndex, coordinateSystem, imageIndex, indexSchema);
      collection.add(feature);
    }
  }

  /**
     * Create a SimpleFeature on top of the provided variable and indexes.
     * 
     * @param variable the input variable 
     * @param tIndex the time index 
     * @param zIndex the zeta index
     * @param cs the {@link CoordinateSystem} associated with that variable
     * @param imageIndex the index to be associated to the feature in the index
     * @param indexSchema the schema to be used to create the feature
     * @param geometry the geometry to be attached to the feature
     * @return the created {@link SimpleFeature}
     * TODO move to variable wrapper
     */
  private SimpleFeature createFeature(final Variable variable, final String coverageName, final int tIndex, final int zIndex, final CoordinateSystem cs, final int imageIndex, final SimpleFeatureType indexSchema) {
    final Date date = getTimeValueByIndex(variable, tIndex, cs);
    final Number verticalValue = getVerticalValueByIndex(variable, zIndex, cs);
    final int dimSize = variable.getDimensions().size();
    final SimpleFeature feature = DataUtilities.template(indexSchema);
    feature.setAttribute(CoverageSlice.Attributes.GEOMETRY, NetCDFCRSUtilities.GEOM_FACTORY.toGeometry(reader.georeferencing.getBoundingBox(variable.getShortName())));
    feature.setAttribute(CoverageSlice.Attributes.INDEX, imageIndex);
    String timeAttribute = null;
    if (date != null) {
      timeAttribute = getTimeAttribute(cs);
      feature.setAttribute(timeAttribute, date);
    }
    List<AttributeDescriptor> descriptors = indexSchema.getAttributeDescriptors();
    if (!Double.isNaN(verticalValue.doubleValue())) {
      String attribute = null;
      final String elevationCVName = reader.georeferencing.getDimension(NetCDFUtilities.ELEVATION_DIM);
      for (AttributeDescriptor descriptor : descriptors) {
        if (descriptor.getLocalName().equalsIgnoreCase(elevationCVName)) {
          attribute = elevationCVName;
          break;
        }
      }
      if (attribute == null) {
        String attrib = null;
        for (int i = 0; i < dimSize; i++) {
          attrib = variable.getDimension(i).getShortName();
          if (!attrib.equalsIgnoreCase(timeAttribute)) {
            attribute = attrib;
            break;
          }
        }
      }
      feature.setAttribute(attribute, verticalValue);
    }
    return feature;
  }

  private String getTimeAttribute(CoordinateSystem cs) {
    CoordinateAxis timeAxis = cs.getTaxis();
    String name = timeAxis.getFullName();
    String timeAttribute = reader.georeferencing.getDimension(name.toUpperCase());
    if (timeAttribute == null) {
      timeAttribute = reader.georeferencing.getDimension(NetCDFUtilities.TIME_DIM);
    }
    return timeAttribute;
  }

  /** Return the zIndex-th value of the vertical dimension of the specified variable, as a double, or {@link Double#NaN} 
     * in case that variable doesn't have a vertical axis.
     * 
     * @param unidataReader the reader to be used for that search
     * @param variable the variable to be accessed
     * @param timeIndex the requested index
     * @param cs the coordinateSystem to be scan
     * @return
     * TODO move to variable wrapper
     */
  private Number getVerticalValueByIndex(Variable variable, final int zIndex, final CoordinateSystem cs) {
    double ve = Double.NaN;
    if (cs != null && cs.hasVerticalAxis()) {
      final int rank = variable.getRank();
      final Dimension verticalDimension = variable.getDimension(rank - NetCDFUtilities.Z_DIMENSION);
      return (Number) reader.georeferencing.getCoordinateVariable(verticalDimension.getFullName()).read(zIndex);
    }
    return ve;
  }

  /** Return the timeIndex-th value of the time dimension of the specified variable, as a Date, or null in case that
     * variable doesn't have a time axis.
     * 
     * @param unidataReader the reader to be used for that search
     * @param variable the variable to be accessed
     * @param timeIndex the requested index
     * @param cs the coordinateSystem to be scan
     * @return
     * TODO move to variable wrapper
     */
  private Date getTimeValueByIndex(Variable variable, int timeIndex, final CoordinateSystem cs) {
    if (cs != null && cs.hasTimeAxis()) {
      final int rank = variable.getRank();
      final Dimension temporalDimension = variable.getDimension(rank - ((cs.hasVerticalAxis() ? NetCDFUtilities.Z_DIMENSION : 2) + 1));
      return (Date) reader.georeferencing.getCoordinateVariable(temporalDimension.getFullName()).read(timeIndex);
    }
    return null;
  }

  static class CoordinateSystemAdapter extends CoordinateSystem {
    /**Input coordinate system*/
    private CoordinateSystem cs;

    /** Boolean indicating that the vertical axis is present*/
    private final boolean vertical;

    CoordinateSystemAdapter(CoordinateSystem cs) {
      this.cs = cs;
      if (cs.hasVerticalAxis()) {
        vertical = true;
      } else {
        Set<String> unsupported = NetCDFUtilities.getUnsupportedDimensions();
        boolean present = false;
        for (String dimension : unsupported) {
          if (cs.containsAxis(dimension)) {
            present = true;
            break;
          }
        }
        if (present) {
          vertical = true;
        } else {
          vertical = false;
        }
      }
    }

    @Override public boolean hasVerticalAxis() {
      return vertical;
    }

    @Override public boolean hasTimeAxis() {
      return cs.hasTimeAxis();
    }

    @Override public CoordinateAxis getTaxis() {
      return cs.getTaxis();
    }

    @Override public List<CoordinateAxis> getCoordinateAxes() {
      return cs.getCoordinateAxes();
    }
  }
}