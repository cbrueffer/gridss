package au.edu.wehi.socrates;

import java.util.Iterator;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.util.ProgressLoggerInterface;

import com.google.common.collect.AbstractIterator;

public class ProgressLoggingSAMRecordIterator extends AbstractIterator<SAMRecord> {
	private ProgressLoggerInterface logger;
	private Iterator<SAMRecord> iterator;
	public ProgressLoggingSAMRecordIterator(Iterator<SAMRecord> iterator, ProgressLoggerInterface logger) {
		this.iterator = iterator;
		this.logger = logger;
	}
	@Override
	protected SAMRecord computeNext() {
		if (iterator.hasNext()) {
			SAMRecord rec = iterator.next();
			if (this.logger != null) logger.record(rec);
			return rec;
		}
		return endOfData();
	}
}