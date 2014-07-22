package au.edu.wehi.idsv;

import static org.junit.Assert.*;

import java.util.Set;

import htsjdk.samtools.SAMRecord;
import htsjdk.variant.vcf.VCFHeaderLineCount;
import htsjdk.variant.vcf.VCFHeaderLineType;

import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import au.edu.wehi.idsv.vcf.VcfAttributes;
import au.edu.wehi.idsv.vcf.VcfSvConstants;
import au.edu.wehi.idsv.vcf.VcfAttributes.Subset;


public class AssemblyBuilderTest extends TestHelper {
	@Test
	public void should_filter_fully_reference_assemblies() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.anchorLength(2)
			.referenceAnchor(0, 1)
			.direction(BWD);
		assertTrue(ab.makeVariant().isFiltered());
	}
	@Test
	public void should_filter_single_read_assemblies() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.anchorLength(1)
			.referenceAnchor(0, 1)
			.direction(BWD)
			.contributingEvidence(Lists.newArrayList((DirectedEvidence)SCE(FWD, Read(0, 1, "1M2S"))));
		assertTrue(ab.makeVariant().isFiltered());
	}
	@Test
	public void should_filter_mate_anchored_assembly_shorter_than_read_length() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.mateAnchor(0, 1)
			.direction(BWD)
			.contributingEvidence(Lists.newArrayList((DirectedEvidence)NRRP(OEA(0, 1, "3M", false))));
		assertTrue(ab.makeVariant().isFiltered());
	}
	@Test
	public void read_length_filter_should_not_apply_to_anchored_breakend_assembly() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.anchorLength(1)
			.referenceAnchor(0, 1)
			.direction(BWD)
			.contributingEvidence(Lists.newArrayList(
					(DirectedEvidence)NRRP(OEA(0, 1, "3M", false)),
					(DirectedEvidence)NRRP(OEA(0, 1, "4M", false))));
		assertFalse(ab.makeVariant().isFiltered());
	}
	@Test
	public void soft_clip_size_filter_should_not_apply_to_unanchored_assembly() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.mateAnchor(0, 1)
			.direction(BWD)
			.contributingEvidence(Lists.newArrayList(
					(DirectedEvidence)NRRP(OEA(0, 1, "3M", false)),
					(DirectedEvidence)NRRP(OEA(0, 1, "4M", false))));
		assertFalse(ab.makeVariant().isFiltered());
	}
	@Test
	public void should_assembly_base_qualities() {
		AssemblyBuilder ab = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AA"))
			.referenceAnchor(0, 1)
			.direction(BWD);
		assertFalse(ab.makeVariant().isFiltered());
	}
	@Test
	public void should_set_assembly_consensus() {
		VariantContextDirectedEvidence dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("GTAC"))
			.referenceAnchor(0, 1)
			.direction(BWD)
			.assembledBaseCount(5, 7)
			.makeVariant();
		assertEquals("GTAC", dba.getAssemblyConsensus());
	}
	@Test
	public void should_set_assembler_name() {
		VariantContextDirectedEvidence dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.referenceAnchor(0, 1)
			.direction(BWD)
			.assemblerName("testAssembler")
			.makeVariant();
		assertEquals("testAssembler", dba.getAssemblerProgram());
	}
	@Test
	public void should_set_breakend_sequence() {
		assertEquals("GT", new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("GTAC"))
			.referenceAnchor(0, 1)
			.direction(BWD)
			.anchorLength(2)
			.makeVariant().getBreakpointSequenceString());
		assertEquals("AC", new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("GTAC"))
			.referenceAnchor(0, 10)
			.direction(FWD)
			.anchorLength(2)
			.makeVariant().getBreakpointSequenceString());
	}
	@Test
	public void should_breakend_to_reference_anchor() {
		VariantContextDirectedEvidence dba;
		BreakendSummary bs;
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.referenceAnchor(1, 10)
			.direction(BWD)
			.anchorLength(2)
			.makeVariant();
		bs = dba.getBreakendSummary();
		assertEquals(BWD, bs.direction);
		assertEquals(1, bs.referenceIndex);
		assertEquals(10, bs.start);
		assertEquals(10, bs.end);
		
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.referenceAnchor(1, 10)
			.direction(FWD)
			.anchorLength(2)
			.makeVariant();
		bs = dba.getBreakendSummary();
		assertEquals(FWD, bs.direction);
		assertEquals(1, bs.referenceIndex);
		assertEquals(10, bs.start);
		assertEquals(10, bs.end);
	}
	@Test
	public void alt_allele_should_contain_only_breakend() {
		VariantContextDirectedEvidence dba;
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAAAAAAGTAC"))
			.referenceAnchor(1, 100)
			.direction(FWD)
			.anchorLength(10)
			.makeVariant();
		assertEquals("GTAC", dba.getBreakpointSequenceString());
		
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("GTACAAAAAAAAAA"))
			.referenceAnchor(1, 10)
			.direction(BWD)
			.anchorLength(10)
			.makeVariant();
		assertEquals("GTAC", dba.getBreakpointSequenceString());
	}
	@Test
	public void should_set_breakend_to_mate_anchor_interval() {
		VariantContextDirectedEvidence dba;
		BreakendSummary bs;
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.mateAnchor(1, 1000)
			.direction(BWD)
			.makeVariant();
		bs = dba.getBreakendSummary();
		assertEquals(BWD, bs.direction);
		assertEquals(1, bs.referenceIndex);
		assertEquals(1000 - AES().getMetrics().getMaxFragmentSize(), bs.start);
		assertEquals(1000, bs.end);
		
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.mateAnchor(1, 1000)
			.direction(FWD)
			.makeVariant();
		bs = dba.getBreakendSummary();
		assertEquals(FWD, bs.direction);
		assertEquals(1, bs.referenceIndex);
		assertEquals(1000, bs.start);
		assertEquals(1000 + AES().getMetrics().getMaxFragmentSize(), bs.end);
	}
	@Test
	public void should_restrict_mate_anchor_interval_based_on_anchor_positions() {
		// max fragment size = 300
		VariantContextDirectedEvidence dba;
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.mateAnchor(1, 100, 5, 10, 20)
			.direction(FWD)
			.makeVariant();
		dba.getBreakendSummary();
		fail(); // need to design this properly 
		//assertEquals(110, bs.start);
		//assertEquals(120, bs.end);
	}
	@Test
	public void mate_anchor_should_set_imprecise_header() {
		// max fragment size = 300
		VariantContextDirectedEvidence dba;
		dba = new AssemblyBuilder(getContext(), AES())
			.assemblyBases(B("AAAAAA"))
			.mateAnchor(1, 100)
			.direction(FWD)
			.makeVariant();
		dba.getBreakendSummary();
		assertTrue(dba.hasAttribute(VcfSvConstants.IMPRECISE_KEY));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_EVIDENCE_COUNT() {
		assertEquals(1, big().getEvidenceCountAssembly());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_LOG_LIKELIHOOD_RATIO() {
		assertNotEquals(0d, big().getBreakendLogLikelihoodAssembly());
	}
	@Test
	public void should_set_attribute_LOG_LIKELIHOOD_RATIO() {
		assertNotEquals(0d, big().getBreakendLogLikelihood(null));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_MAPPED() {
		assertEquals(0, big().getMappedEvidenceCountAssembly());
		assertEquals(1, bigr().getMappedEvidenceCountAssembly());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_MAPQ_REMOTE_MAX() {
		assertEquals(0, big().getMapqAssemblyRemoteMax());
		assertEquals(7, bigr().getMapqAssemblyRemoteMax());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_MAPQ_REMOTE_TOTAL() {
		assertEquals(0, big().getMapqAssemblyRemoteTotal());
		assertEquals(7, bigr().getMapqAssemblyRemoteTotal());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_LENGTH_LOCAL_MAX() {
		assertEquals(5, big().getAssemblyAnchorLengthMax());
		assertEquals(5, bigr().getAssemblyAnchorLengthMax());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_LENGTH_REMOTE_MAX() {
		assertEquals(3, big().getAssemblyBreakendLengthMax());
		assertEquals(3, bigr().getAssemblyBreakendLengthMax());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_BASE_COUNT() {	
		assertEquals(513, big().getAssemblyBaseCount(Subset.NORMAL));
		assertEquals(745, big().getAssemblyBaseCount(Subset.TUMOUR));
		assertEquals(513, bigr().getAssemblyBaseCount(Subset.NORMAL));
		assertEquals(745, bigr().getAssemblyBaseCount(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_READPAIR_COUNT() {
		assertEquals(2, bigr().getAssemblySupportCountReadPair(Subset.NORMAL));
		assertEquals(4, bigr().getAssemblySupportCountReadPair(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_READPAIR_LENGTH_MAX() {
		assertEquals(10, bigr().getAssemblyReadPairLengthMax(Subset.NORMAL));
		assertEquals(5, bigr().getAssemblyReadPairLengthMax(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_SOFTCLIP_COUNT() {
		assertEquals(1, bigr().getAssemblySupportCountSoftClip(Subset.NORMAL));
		assertEquals(2, bigr().getAssemblySupportCountSoftClip(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL() {	
		assertEquals(4, bigr().getAssemblySoftClipLengthTotal(Subset.NORMAL));
		assertEquals(6, bigr().getAssemblySoftClipLengthTotal(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX() {
		assertEquals(4, bigr().getAssemblySoftClipLengthMax(Subset.NORMAL));
		assertEquals(3, bigr().getAssemblySoftClipLengthMax(Subset.TUMOUR));
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_CONSENSUS() {
		assertEquals("CGTAAAAA", big().getAssemblyConsensus());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_PROGRAM() {
		assertEquals("assemblerName", big().getAssemblerProgram());
	}
	@Test
	public void should_set_assembly_attribute_ASSEMBLY_BREAKEND_QUALS() {
		assertArrayEquals(new byte[] { 0,1,2}, big().getBreakendQuality());
	}
	@Test
	public void should_set_evidence_source() {
		EvidenceSource es = AES();
		assertEquals(es, new AssemblyBuilder(getContext(), AES())
			.direction(FWD)
			.anchorLength(1)
			.referenceAnchor(0, 1)
			.makeVariant().getEvidenceSource());
	}
	public VariantContextDirectedEvidence big() {
		ProcessingContext pc = getContext();
		MockSAMEvidenceSource nes = new MockSAMEvidenceSource(pc);
		MockSAMEvidenceSource tes = new MockSAMEvidenceSource(pc);
		tes.isTumour = true;
		Set<DirectedEvidence> support = Sets.newHashSet();
		support.add(SCE(BWD, nes, Read(0, 10, "4S1M")));
		support.add(SCE(BWD, tes, Read(0, 10, "3S5M")));
		support.add(SCE(BWD, tes, Read(0, 10, "3S6M")));
		support.add(NRRP(nes, OEA(0, 15, "5M", false)));
		support.add(NRRP(tes, OEA(0, 16, "5M", false)));
		support.add(NRRP(tes, OEA(0, 17, "5M", false)));
		support.add(NRRP(tes, DP(0, 1, "2M", true, 0, 15, "5M", false)));
		support.add(NRRP(tes, DP(0, 2, "2M", true, 0, 16, "5M", false)));
		support.add(NRRP(nes, DP(0, 3, "2M", true, 0, 17, "10M", false)));
		AssemblyBuilder sb = new AssemblyBuilder(pc, AES())
			.direction(BWD)
			.anchorLength(5)
			.referenceAnchor(0, 10)
			.assemblerName("assemblerName")
			.assemblyBases(B("CGTAAAAT"))
			.assembledBaseCount(513, 745)
			.contributingEvidence(support)
			.assemblyBaseQuality(new byte[] { 0,1,2,3,4,5,6,7});
		return sb.makeVariant();
	}
	public VariantContextDirectedEvidence bigr() {
		SAMRecord ra = Read(1, 100, "1S1M1S");
		ra.setReadBases(B("CGT"));
		ra.setMappingQuality(7);
		ra.setBaseQualities(new byte[] { 0,1,2});
		return AssemblyBuilder.incorporateRealignment(getContext(), big(), ra);
	}
	@Test
	public void incorporateRealignment_should_convert_to_breakend() {
		assertTrue(bigr() instanceof VariantContextDirectedBreakpoint);
		VariantContextDirectedBreakpoint bp = (VariantContextDirectedBreakpoint)bigr();
		assertEquals(1, bp.getBreakendSummary().referenceIndex2);
	}
	@Test
	public void getAnchorSequenceString_should_return_entire_assembly_anchor() {
		assertEquals("AAAAT", big().getAnchorSequenceString());
	}
}
