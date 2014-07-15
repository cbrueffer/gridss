package au.edu.wehi.idsv;

import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.metrics.Header;
import htsjdk.samtools.metrics.MetricBase;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.reference.ReferenceSequenceFileFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Processing context for the given record
 * @author Daniel Cameron
 *
 */
public class ProcessingContext implements Closeable {
	private final ReferenceSequenceFile reference;
	private final File referenceFile;
	private final SAMSequenceDictionary dictionary;
	private final LinearGenomicCoordinate linear;
	private final FileSystemContext fsContext;
	private final boolean vcf41mode;
	private final boolean perChr;
	private final AssemblyParameters ap;
	private final SoftClipParameters scp;
	private final RealignmentParameters rp;
	private final List<Header> metricsHeaders;
	public ProcessingContext(
			FileSystemContext fileSystemContext,
			List<Header> metricsHeaders,
			SoftClipParameters softClipParameters,
			AssemblyParameters assemblyParameters,
			RealignmentParameters realignmentParameters,
			File ref, boolean perChr, boolean vcf41) {
		this.fsContext = fileSystemContext;
		this.metricsHeaders = metricsHeaders;
		this.scp = softClipParameters;
		this.ap = assemblyParameters;
		this.rp = realignmentParameters;
		this.perChr = perChr;
		this.vcf41mode = vcf41;
		this.referenceFile = ref;
		this.reference = ReferenceSequenceFileFactory.getReferenceSequenceFile(this.referenceFile);
		if (this.reference.getSequenceDictionary() == null) {
			throw new IllegalArgumentException("Missing sequence dictionary for reference genome. Please create using picard CreateSequenceDictionary.");
		}
		this.dictionary = new DynamicSAMSequenceDictionary(this.reference.getSequenceDictionary());
		this.linear = new LinearGenomicCoordinate(this.dictionary);
	}
	/**
	 * Creates a new metrics file with appropriate headers for this context 
	 * @return MetricsFile
	 */
	public <A extends MetricBase,B extends Comparable<?>> MetricsFile<A,B> createMetricsFile() {
        final MetricsFile<A,B> file = new MetricsFile<A,B>();
        for (final Header h : metricsHeaders) {
            file.addHeader(h);
        }
        return file;
    }
	public FileSystemContext getFileSystemContext() {
		return fsContext;
	}
	public SamReaderFactory getSamReaderFactory() {
		return SamReaderFactory.makeDefault()
				.validationStringency(ValidationStringency.LENIENT);
				//.enable(Option.INCLUDE_SOURCE_IN_RECORDS); // don't need as we're tracking ourselves using EvidenceSource
	}
	public SAMFileWriterFactory getSamReaderWriterFactory() {
		return new SAMFileWriterFactory()
			.setTempDirectory(fsContext.getTemporaryDirectory())
			.setUseAsyncIo(true)
			.setCreateIndex(true);
	}
	public FastqWriterFactory getFastqWriterFactory(){
		FastqWriterFactory factory = new FastqWriterFactory();
		factory.setUseAsyncIo(true);
		return factory;
	}
	public ReferenceSequenceFile getReference() {
		return reference;
	}
	public File getReferenceFile() {
		return referenceFile;
	}
	public SAMSequenceDictionary getDictionary() {
		return dictionary;
	}
	public LinearGenomicCoordinate getLinear() {
		return linear;
	}
	public boolean shouldProcessPerChromosome() {
		return perChr;
	}
	/**
	 * Determines whether VCF records should be compatible with VCF v4.1
	 */
	public boolean getVcf41Mode() {
		return vcf41mode;
	}
	@Override
	public void close() throws IOException {
		if (reference != null) reference.close();
	}
	public AssemblyParameters getAssemblyParameters() {
		return ap;
	}
	public SoftClipParameters getSoftClipParameters() {
		return scp;
	}
	public RealignmentParameters getRealignmentParameters() {
		return rp;
	}
}
