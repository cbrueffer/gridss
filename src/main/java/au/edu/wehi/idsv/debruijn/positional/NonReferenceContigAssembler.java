package au.edu.wehi.idsv.debruijn.positional;

import htsjdk.samtools.util.Log;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import au.edu.wehi.idsv.AssemblyEvidenceSource;
import au.edu.wehi.idsv.AssemblyFactory;
import au.edu.wehi.idsv.BreakendDirection;
import au.edu.wehi.idsv.BreakendSummary;
import au.edu.wehi.idsv.Defaults;
import au.edu.wehi.idsv.SAMRecordAssemblyEvidence;
import au.edu.wehi.idsv.debruijn.DeBruijnGraphBase;
import au.edu.wehi.idsv.debruijn.KmerEncodingHelper;
import au.edu.wehi.idsv.graph.ScalingHelper;
import au.edu.wehi.idsv.model.Models;
import au.edu.wehi.idsv.util.IntervalUtil;
import au.edu.wehi.idsv.visualisation.PositionalDeBruijnGraphTracker;
import au.edu.wehi.idsv.visualisation.PositionalDeBruijnGraphTracker.ContigStats;
import au.edu.wehi.idsv.visualisation.PositionalExporter;

import com.google.api.client.util.Lists;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;


/**
 * Calls optimal contigs from a positional de Bruijn graph
 * 
 * @author Daniel Cameron
 *
 */
public class NonReferenceContigAssembler extends AbstractIterator<SAMRecordAssemblyEvidence> {
	private static final Log log = Log.getInstance(NonReferenceContigAssembler.class);
	/**
	 * Debugging tracker to ensure memoization export files have unique names
	 */
	private static final AtomicInteger pathExportCount = new AtomicInteger();
	/**
	 * Positional size of the loaded subgraph before orphan calling is performed.
	 * This is high as orphaned subgraphs are relatively uncommon
	 * This value is in multiples of maxEvidenceDistance
	 */
	private static final float ORPHAN_EVIDENCE_MULTIPLE = 128;
	/**
	 * If the iterator has been advanced this many times without a best contig
	 * identified, trigger the misassembly identification logic
	 */
	private static final int LONGEST_PATH_REMOVAL_ADVANCEMENT_TRIGGER_COUNT = 2;
	/**
	 * Since reference kmers are not scored, calculating 
	 * highest weighted results in a preference for paths
	 * ending at a RP with sequencing errors over a path
	 * anchored to the reference. 
	 * 
	 * To ensure that the anchored paths are scored higher
	 * than the unanchored paths, paths anchored to the
	 * reference are given a score adjustment larger than
	 * the largest expected score.
	 */
	static final int ANCHORED_SCORE = Integer.MAX_VALUE >> 2;
	/**
	 * TODO: check to see if this is worth doing
	 * Simplication reduces the graph size, but may trigger
	 * additional rememoization so might turn out to be a more
	 * expensive approach overall
	 */
	private static final boolean SIMPLIFY_AFTER_REMOVAL = false; 
	private Long2ObjectMap<Collection<KmerPathNodeKmerNode>> graphByKmerNode = new Long2ObjectOpenHashMap<Collection<KmerPathNodeKmerNode>>();
	private SortedSet<KmerPathNode> graphByPosition = new TreeSet<KmerPathNode>(KmerNodeUtil.ByFirstStartKmer);
	private final EvidenceTracker evidenceTracker;
	private final AssemblyEvidenceSource aes;
	/**
	 * Worst case scenario is a RP providing single kmer support for contig
	 * read length - (k-1) + max-min fragment size
	 *
	 * ========== contig
	 *          --------- read contributes single kmer to contig  
	 *           \       \  in the earliest position
	 *            \  RP   \
	 *             \       \
	 *              \       \
	 *               ---------
	 *                        ^
	 *                        |
	 * Last position supported by this RP is here. 
	 */
	private final int maxEvidenceDistance;
	private final int maxAnchorLength;
	private final int k;
	private final int referenceIndex;
	private final ContigStats stats = new ContigStats();
	private final PeekingIterator<KmerPathNode> underlying;
	private final String contigName;
	private int lastUnderlyingStartPosition = Integer.MIN_VALUE;
	private MemoizedContigCaller bestContigCaller;
	private MemoizedContigCaller bestUnanchoredContigCaller = null;
	private int contigsCalled = 0;
	private long consumed = 0;
	private PositionalDeBruijnGraphTracker exportTracker = null;
	public int getReferenceIndex() { return referenceIndex; }
	/**
	 * Creates a new structural variant positional de Bruijn graph contig assembly for the given chromosome
	 * @param it reads
	 * @param referenceIndex evidence source
	 * @param maxEvidenceDistance maximum distance from the first position of the first kmer of a read,
	 *  to the last position of the last kmer of a read. This should be set to read length plus
	 *  the max-min concordant fragment size
	 * @param maxAnchorLength maximum number of reference-supporting anchor bases to assemble
	 * @param k
	 * @param source assembly source
	 * @param tracker evidence lookup
	 */
	public NonReferenceContigAssembler(
			Iterator<KmerPathNode> it,
			int referenceIndex,
			int maxEvidenceDistance,
			int maxAnchorLength,
			int k,
			AssemblyEvidenceSource source,
			EvidenceTracker tracker,
			String contigName) {
		this.underlying = Iterators.peekingIterator(it);
		this.maxEvidenceDistance = maxEvidenceDistance;
		this.maxAnchorLength = maxAnchorLength;
		this.k = k;
		this.referenceIndex = referenceIndex;
		this.aes = source;
		this.evidenceTracker = tracker;
		this.contigName = contigName;
		initialiseBestCaller();
	}
	private void initialiseBestCaller() {
		this.bestContigCaller = new MemoizedContigCaller(ANCHORED_SCORE, maxEvidenceDistance);
		for (KmerPathNode n : graphByPosition) {
			bestContigCaller.add(n);
		}
	}
	private void initialiseUnanchoredCaller() {
		 // +ve per-node weight required
		this.bestUnanchoredContigCaller = new MemoizedContigCaller(1, maxEvidenceDistance);
		for (KmerPathNode n : graphByPosition) {
			bestUnanchoredContigCaller.add(n);
		}
	}
	@Override
	protected SAMRecordAssemblyEvidence computeNext() {
		SAMRecordAssemblyEvidence calledContig;
		do {
			ArrayDeque<KmerPathSubnode> bestContig = findBestContig();
			if (bestContig == null) {
				// no more contigs :(
				if (underlying.hasNext()) {
					log.error("Sanity check failure: end of contigs called before all evidence loaded " + contigName);
				}
				removeOrphanedNonReferenceSubgraphs();
				if (!graphByPosition.isEmpty()) {
					log.error("Sanity check failure: non-empty graph with no contigs called " + contigName);
				}
				return endOfData();
			}
			calledContig = callContig(bestContig);
		} while (calledContig == null); // if we filtered out our contig, go back
		return calledContig;
	}
	private static final ArrayDeque<KmerPathSubnode> EMPTY_CONTIG = new ArrayDeque<>();
	private boolean isMisassembledContig(ArrayDeque<KmerPathSubnode> contig) {
		if (contig == null) return false;
		int contigLength = contig.stream().mapToInt(sn -> sn.length()).sum();
		return contigLength > aes.getContext().getAssemblyParameters().maxExpectedBreakendLengthMultiple * aes.getMaxConcordantFragmentSize();
	}
	private ArrayDeque<KmerPathSubnode> findBestContig() {
		ArrayDeque<KmerPathSubnode> bestUnanchoredContig = bestUnanchoredContigCaller == null ? EMPTY_CONTIG : bestUnanchoredContigCaller.bestContig(nextPosition());
		ArrayDeque<KmerPathSubnode> bestContig = bestContigCaller.bestContig(nextPosition());
		int advanceCount = 0;
		while (underlying.hasNext() && (bestUnanchoredContig == null || bestContig == null)) {
			advanceUnderlying();
			advanceCount++;
			// Early abort in regions prone to misassembly
			if (advanceCount >= LONGEST_PATH_REMOVAL_ADVANCEMENT_TRIGGER_COUNT) {
				if (bestUnanchoredContigCaller == null) {
					initialiseUnanchoredCaller();
				}
				bestUnanchoredContig = bestUnanchoredContigCaller.bestContig(nextPosition());
				while (isMisassembledContig(bestUnanchoredContig)) {
					log.info(String.format("Missassembled contig detected at positions %s:%d-%d. Not assembling reads supporting misassembly.",
							contigName,
							bestUnanchoredContig.getFirst().firstStart(),
							bestUnanchoredContig.getLast().lastEnd()));
					Set<KmerEvidence> evidence = evidenceTracker.untrack(bestUnanchoredContig);
					// this will be a large change to the memoization
					// so it's usually faster to just recalculate from scratch
					bestContigCaller = null;
					bestUnanchoredContigCaller = null;
					removeFromGraph(evidence);
					initialiseUnanchoredCaller();
					bestUnanchoredContig = bestUnanchoredContigCaller.bestContig(nextPosition());
				}
				if (bestContigCaller == null) {
					initialiseBestCaller();
				}
			}
			bestContig = bestContigCaller.bestContig(nextPosition());
		}
		if (advanceCount == 0) {
			// if we haven't advanced, we can turn large contig checking back off
			bestUnanchoredContigCaller = null;
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION) {
			assert(bestContigCaller.sanityCheckFrontier(nextPosition()));
			verifyMemoization();
		}
		if (aes.getContext().getConfig().getVisualisation().assemblyContigMemoization) {
			File file = new File(aes.getContext().getConfig().getVisualisation().directory, "assembly.path.memoization." + contigName + "." + Integer.toString(pathExportCount.incrementAndGet()) + ".csv");
			try {
				bestContigCaller.exportState(file);
			} catch (IOException e) {
				log.debug(e, " Unable to export assembly path memoization to ", file, " ", contigName);
			}
		}
		return bestContig;
	}
	private int nextPosition() {
		if (!underlying.hasNext()) return Integer.MAX_VALUE;
		return underlying.peek().firstStart();
	}
	/**
	 * Loads additional nodes into the graph
	 * 
	 * By loaded in batches, we reduce our memoization runtime
	 */
	private void advanceUnderlying() {
		int loadUntil = nextPosition();
		if (loadUntil < Integer.MAX_VALUE) {
			loadUntil += maxEvidenceDistance + 1;
		}
		removeOrphanedNonReferenceSubgraphs();
		advanceUnderlying(loadUntil);
	}
	private void advanceUnderlying(int loadUntil) {
		while (underlying.hasNext() && nextPosition() <= loadUntil) {
			KmerPathNode node = underlying.next();
			assert(lastUnderlyingStartPosition <= node.firstStart());
			lastUnderlyingStartPosition = node.firstStart();
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				assert(evidenceTracker.matchesExpected(new KmerPathSubnode(node)));
			}
			addToGraph(node);
			consumed++;
		}
	}
	/**
	 * Verifies that the memoization matches a freshly calculated memoization 
	 * @param contig
	 */
	private boolean verifyMemoization() {
		int preGraphSize = graphByPosition.size();
		MemoizedContigCaller mcc = new MemoizedContigCaller(ANCHORED_SCORE, maxEvidenceDistance);
		for (KmerPathNode n : graphByPosition) {
			mcc.add(n);
		}
		mcc.bestContig(nextPosition());
		bestContigCaller.sanityCheckMatches(mcc);
		int postGraphSize = graphByPosition.size();
		assert(preGraphSize == postGraphSize);
		return true;
	}
	private SAMRecordAssemblyEvidence callContig(ArrayDeque<KmerPathSubnode> rawcontig) {
		ArrayDeque<KmerPathSubnode> contig = rawcontig;
		if (containsKmerRepeat(contig)) {
			// recalculate the called contig, this may break the contig at the repeated kmer
			MisassemblyFixer fixed = new MisassemblyFixer(contig);
			contig = new ArrayDeque<KmerPathSubnode>(fixed.correctMisassignedEvidence(evidenceTracker.support(contig)));
		}
		if (contig.isEmpty()) return null;
		Set<KmerEvidence> evidence = evidenceTracker.untrack(contig);
		
		int targetAnchorLength = Math.max(contig.stream().mapToInt(sn -> sn.length()).sum(), maxAnchorLength);
		KmerPathNodePath startAnchorPath = new KmerPathNodePath(contig.getFirst(), false, targetAnchorLength + maxEvidenceDistance + contig.getFirst().length());
		startAnchorPath.greedyTraverse(true, false);
		ArrayDeque<KmerPathSubnode> startingAnchor = startAnchorPath.headNode().asSubnodes();
		startingAnchor.removeLast();
		// make sure we have enough of the graph loaded so that when
		// we traverse forward, our anchor sequence will be fully defined
		advanceUnderlying(contig.getLast().lastEnd() + targetAnchorLength + maxEvidenceDistance);
		KmerPathNodePath endAnchorPath = new KmerPathNodePath(contig.getLast(), true, targetAnchorLength + maxEvidenceDistance + contig.getLast().length());
		endAnchorPath.greedyTraverse(true, false);
		ArrayDeque<KmerPathSubnode> endingAnchor = endAnchorPath.headNode().asSubnodes();
		endingAnchor.removeFirst();
		
		List<KmerPathSubnode> fullContig = new ArrayList<KmerPathSubnode>(contig.size() + startingAnchor.size() + endingAnchor.size());
		fullContig.addAll(startingAnchor);
		fullContig.addAll(contig);
		fullContig.addAll(endingAnchor);
		
		byte[] bases = KmerEncodingHelper.baseCalls(fullContig.stream().flatMap(sn -> sn.node().pathKmers().stream()).collect(Collectors.toList()), k);
		byte[] quals = DeBruijnGraphBase.kmerWeightsToBaseQuals(k, fullContig.stream().flatMapToInt(sn -> sn.node().pathWeights().stream().mapToInt(Integer::intValue)).toArray());
		assert(quals.length == bases.length);
		// left aligned anchor position although it shouldn't matter since anchoring should be a single base wide
		int startAnchorPosition = startingAnchor.size() == 0 ? 0 : startingAnchor.getLast().lastStart() + k - 1;
		int endAnchorPosition = endingAnchor.size() == 0 ? 0 : endingAnchor.getFirst().firstStart();
		int startAnchorBaseCount = startingAnchor.size() == 0 ? 0 : startingAnchor.stream().mapToInt(n -> n.length()).sum() + k - 1;
		int endAnchorBaseCount = endingAnchor.size() == 0 ? 0 : endingAnchor.stream().mapToInt(n -> n.length()).sum() + k - 1;
		int startBasesToTrim = Math.max(0, startAnchorBaseCount - targetAnchorLength);
		int endingBasesToTrim = Math.max(0, endAnchorBaseCount - targetAnchorLength);
		bases = Arrays.copyOfRange(bases, startBasesToTrim, bases.length - endingBasesToTrim);
		quals = Arrays.copyOfRange(quals, startBasesToTrim, quals.length - endingBasesToTrim);
		
		List<String> evidenceIds = evidence.stream().map(e -> e.evidenceId()).collect(Collectors.toList());
		SAMRecordAssemblyEvidence assembledContig;
		if (startingAnchor.size() == 0 && endingAnchor.size() == 0) {
			assert(startBasesToTrim == 0);
			assert(endingBasesToTrim == 0);
			// unanchored
			BreakendSummary be = Models.calculateBreakend(aes.getContext().getLinear(),
					evidence.stream().map(e -> e.breakend()).collect(Collectors.toList()),
					evidence.stream().map(e -> ScalingHelper.toScaledWeight(e.evidenceQuality())).collect(Collectors.toList()));
			assembledContig = AssemblyFactory.createUnanchoredBreakend(aes.getContext(), aes,
					be,
					evidenceIds,
					bases, quals, new int[] { 0, 0 });
			if (evidence.stream().anyMatch(e -> e.isAnchored())) {
				log.debug(String.format("Unanchored assembly %s at %s:%d contains anchored evidence", assembledContig.getEvidenceID(), contigName, contig.getFirst().firstStart()));
			}
		} else if (startingAnchor.size() == 0) {
			// end anchored
			assembledContig = AssemblyFactory.createAnchoredBreakend(aes.getContext(), aes,
					BreakendDirection.Backward, evidenceIds,
					referenceIndex, endAnchorPosition, endAnchorBaseCount - endingBasesToTrim,
					bases, quals);
		} else if (endingAnchor.size() == 0) {
			// start anchored
			assembledContig = AssemblyFactory.createAnchoredBreakend(aes.getContext(), aes,
					BreakendDirection.Forward, evidenceIds,
					referenceIndex, startAnchorPosition, startAnchorBaseCount - startBasesToTrim,
					bases, quals);
		} else {
			if (startAnchorBaseCount + endAnchorBaseCount >= quals.length) {
				// no unanchored bases - not an SV assembly
				assembledContig = null;
			} else {
				assembledContig = AssemblyFactory.createAnchoredBreakpoint(aes.getContext(), aes, evidenceIds,
						referenceIndex, startAnchorPosition, startAnchorBaseCount - startBasesToTrim,
						referenceIndex, endAnchorPosition, endAnchorBaseCount - endingBasesToTrim,
						bases, quals);
			}
		}
		if (assembledContig != null) {
			if (aes.getContext().getConfig().getVisualisation().assemblyGraph) {
				try {
					PositionalExporter.exportDot(new File(aes.getContext().getConfig().getVisualisation().directory, "assembly." + contigName + "." + assembledContig.getEvidenceID() + ".dot"), k, graphByPosition, fullContig);
				} catch (Exception ex) {
					log.debug(ex, "Error exporting assembly ", assembledContig != null ? assembledContig.getEvidenceID() : "(null)", " ", contigName);
				}
			}
			if (aes.getContext().getConfig().getVisualisation().assemblyGraphFullSize) {
				try {
					PositionalExporter.exportNodeDot(new File(aes.getContext().getConfig().getVisualisation().directory, "assembly.fullsize." + contigName + "." + assembledContig.getEvidenceID() + ".dot"), k, graphByPosition, fullContig);
				} catch (Exception ex) {
					log.debug(ex, "Error exporting assembly ", assembledContig != null ? assembledContig.getEvidenceID() : "(null)", " ", contigName);
				}
			}
		}
		stats.contigNodes = contig.size();
		stats.truncatedNodes = rawcontig.size() - contig.size();
		stats.contigStartPosition = contig.getFirst().firstStart();
		stats.startAnchorNodes = startingAnchor.size();
		stats.endAnchorNodes = endingAnchor.size();
		if (exportTracker != null) {
			exportTracker.trackAssembly(bestContigCaller);
		}
		// remove all evidence contributing to this assembly from the graph
		if (evidence.size() > 0) {
			removeFromGraph(evidence);
			if (Defaults.SANITY_CHECK_MEMOIZATION) {
				bestContigCaller.sanityCheck(graphByPosition);
			}
		} else {
			log.error("Sanity check failure: found path with no support. Attempting to recover by direct node removal ", contigName);
			for (KmerPathSubnode n : contig) {
				removeFromGraph(n.node(), true);
			}
		}
		contigsCalled++;
		return assembledContig;
	}
	private boolean containsKmerRepeat(Collection<KmerPathSubnode> contig) {
		LongSet existing = new LongOpenHashSet();
		for (KmerPathSubnode n : contig) {
			for (int i = 0; i < n.length(); i++) {
				if (!existing.add(n.node().kmer(i))) {
					return true;
				}
			}
			for (long kmer : n.node().collapsedKmers()) {
				if (!existing.add(kmer)) {
					return true;
				}
			}
		}
		return false;
	}
	/**
	 * Removes all evidence from the current graph
	 * @param evidence
	 */
	private void removeFromGraph(Set<KmerEvidence> evidence) {
		assert(!evidence.isEmpty());
		Map<KmerPathNode, List<List<KmerNode>>> toRemove = new IdentityHashMap<KmerPathNode, List<List<KmerNode>>>();
		for (KmerEvidence e : evidence) {
			for (int i = 0; i < e.length(); i++) {
				KmerSupportNode support = e.node(i);
				if (support != null) {
					if (support.lastEnd() >= nextPosition()) {
						log.error(String.format("Sanity check failure: %s extending to %d removed when input at %s:%d", e, support.lastEnd(), contigName, nextPosition()));
						// try to recover
					}
					updateRemovalList(toRemove, support);
				}
			}
		}
		if (bestContigCaller != null) {
			bestContigCaller.remove(toRemove.keySet());
		}
		if (bestUnanchoredContigCaller != null) {
			bestUnanchoredContigCaller.remove(toRemove.keySet());
		}
		Set<KmerPathNode> simplifyCandidates = new ObjectOpenCustomHashSet<KmerPathNode>(new KmerPathNode.HashByFirstKmerStartPositionKmer<KmerPathNode>());
		for (Entry<KmerPathNode, List<List<KmerNode>>> entry : toRemove.entrySet()) {
			removeWeight(entry.getKey(), entry.getValue(), simplifyCandidates, false);
		}
		if (SIMPLIFY_AFTER_REMOVAL) {
			simplify(simplifyCandidates);
		}
		if (Defaults.SANITY_CHECK_DE_BRUIJN) {
			assert(sanityCheck());
			assert(sanityCheckDisjointNodeIntervals());
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION && bestContigCaller != null) {
			// Force memoization recalculation now
			bestContigCaller.bestContig(nextPosition());
			// so we can check that our removal was correct
			verifyMemoization();
		}
	}
	/**
	 * Attempts to simplify the given nodes
	 * @param simplifyCandidates
	 */
	private void simplify(Set<KmerPathNode> simplifyCandidates) {
		while (!simplifyCandidates.isEmpty()) {
			simplify(simplifyCandidates.iterator().next(), simplifyCandidates);
		}
	}
	private void simplify(KmerPathNode node, Set<KmerPathNode> simplifyCandidates) {
		simplifyCandidates.remove(node);
		if (node.lastEnd() >= nextPosition() - 1) {
			// don't simplify graph if we haven't actually loaded all the relevant nodes
			return;
		}
		KmerPathNode prev = node.prevToMergeWith();
		if (prev != null && prev.lastEnd() < nextPosition() - 1) {
			simplifyCandidates.remove(prev);
			removeFromGraph(node, true);
			removeFromGraph(prev, true);
			node.prepend(prev);
			addToGraph(node);
		}
		KmerPathNode next = node.nextToMergeWith();
		if (next != null && next.lastEnd() < nextPosition() - 1) {
			simplifyCandidates.remove(next);
			removeFromGraph(node, true);
			removeFromGraph(next, true);
			next.prepend(node);
			addToGraph(next);
		}
	}
	private void updateRemovalList(Map<KmerPathNode, List<List<KmerNode>>> toRemove, KmerSupportNode support) {
		Collection<KmerPathNodeKmerNode> kpnknList = graphByKmerNode.get(support.lastKmer());
		if (kpnknList != null) {
			for (KmerPathNodeKmerNode n : kpnknList) {
				if (IntervalUtil.overlapsClosed(support.lastStart(), support.lastEnd(), n.lastStart(), n.lastEnd())) {
					updateRemovalList(toRemove, n, support);
				}
			}
		}
	}
	private void updateRemovalList(Map<KmerPathNode, List<List<KmerNode>>> toRemove, KmerPathNodeKmerNode node, KmerSupportNode support) {
		KmerPathNode pn = node.node();
		List<List<KmerNode>> list = toRemove.get(pn);
		if (list == null) {
			list = new ArrayList<List<KmerNode>>(pn.length());
			toRemove.put(pn, list);
		}
		int offset = node.offsetOfPrimaryKmer();
		while (list.size() <= offset) {
			list.add(null);
		}
		List<KmerNode> evidenceList = list.get(offset); 
		if (evidenceList == null) {
			evidenceList = new ArrayList<KmerNode>();
			list.set(offset, evidenceList);
		}
		evidenceList.add(support);
	}
	private void removeWeight(KmerPathNode node, List<List<KmerNode>> toRemove, Set<KmerPathNode> simplifyCandidates, boolean includeMemoizationRemoval) {
		if (node == null) return;
		assert(node.length() >= toRemove.size());
		// remove from graph
		removeFromGraph(node, includeMemoizationRemoval);
		simplifyCandidates.addAll(node.next());
		simplifyCandidates.addAll(node.prev());
		simplifyCandidates.remove(node);
		Collection<KmerPathNode> replacementNodes = KmerPathNode.removeWeight(node, toRemove);
		for (KmerPathNode split : replacementNodes) {
			if (Defaults.SANITY_CHECK_DE_BRUIJN) {
				assert(evidenceTracker.matchesExpected(new KmerPathSubnode(split)));
			}
			addToGraph(split);
		}
		simplifyCandidates.addAll(replacementNodes);
	}
	private void addToGraph(KmerPathNode node) {
		boolean added = graphByPosition.add(node);
		assert(added);
		for (int i = 0; i < node.length(); i++) {
			addToGraph(new KmerPathNodeKmerNode(node, i));
		}
		for (int i = 0; i < node.collapsedKmers().size(); i++) {
			addToGraph(new KmerPathNodeKmerNode(i, node));
		}
		if (bestContigCaller != null) {
			bestContigCaller.add(node);
		}
		if (bestUnanchoredContigCaller != null) {
			bestUnanchoredContigCaller.add(node);
		}
	}
	private void removeFromGraph(KmerPathNode node, boolean includeMemoizationRemoval) {
		if (includeMemoizationRemoval) {
			if (bestContigCaller != null) {
				bestContigCaller.remove(node);
			}
			if (bestUnanchoredContigCaller != null) {
				bestUnanchoredContigCaller.remove(node);
			}
		}
		boolean removed = graphByPosition.remove(node);
		assert(removed);
		for (int i = 0; i < node.length(); i++) {
			removeFromGraph(new KmerPathNodeKmerNode(node, i));
		}
		for (int i = 0; i < node.collapsedKmers().size(); i++) {
			removeFromGraph(new KmerPathNodeKmerNode(i, node));
		}
	}
	private void addToGraph(KmerPathNodeKmerNode node) {
		Collection<KmerPathNodeKmerNode> list = graphByKmerNode.get(node.firstKmer());
		if (list == null) {
			list = new ArrayList<KmerPathNodeKmerNode>();
			graphByKmerNode.put(node.firstKmer(), list);
		}
		list.add(node);
	}
	private void removeFromGraph(KmerPathNodeKmerNode node) {
		Collection<KmerPathNodeKmerNode> list = graphByKmerNode.get(node.firstKmer());
		if (list == null) return;
		list.remove(node);
		if (list.size() == 0) {
			graphByKmerNode.remove(node.firstKmer());
		}
	}
	/**
	 * Detects and removes orphaned reference subgraphs.
	 * 
	 * Orphaned reference subgraphs can occur when all non-reference
	 * kmers for a given evidence are merged with reference kmers.
	 * Eg: a sequencing error causes a short soft clip leaf which
	 * is subsequently merged with the reference allele.  
	 * 
	 * No contigs will be called for such evidence since no all
	 * connected kmers are reference kmers.
	 * 
	 * @author Daniel Cameron
	 *
	 */
	private void removeOrphanedNonReferenceSubgraphs() {
		if (graphByPosition.isEmpty()) return;
		if (graphByPosition.first().firstStart() >= nextPosition() - ORPHAN_EVIDENCE_MULTIPLE * maxEvidenceDistance) return;
		int lastEnd = Integer.MIN_VALUE;
		List<KmerPathNode> nonRefOrphaned = new ArrayList<KmerPathNode>();
		List<KmerPathNode> nonRefActive = new ArrayList<KmerPathNode>();
		// Since we're calling all non-reference contig
		// all orphaned reference subgraph will eventually
		// have no reference kmers with any overlapping positions
		// This means we don't need to actually calculate the subgraph
		// just wait until we've called all the reference contigs and
		// we're just left the non-reference.
		
		// Note: this approach delays removal until all overlapping non-reference
		// subgraphs do not overlap evidence
		// In actual data this does not occur often so is not too much of an issue.
		for (KmerPathNode n : graphByPosition) {
			if (lastEnd < n.firstStart() - 1) {
				// can't be connected since all active nodes
				// have finished sufficiently early that
				// they can't be connected
				nonRefOrphaned.addAll(nonRefActive);
				nonRefActive.clear();
			}
			if (!n.isReference() || n.lastEnd() >= nextPosition() - maxEvidenceDistance) {
				// could connect to a reference node
				nonRefActive.clear();
				break;
			}
			lastEnd = Math.max(lastEnd, n.lastEnd());
			nonRefActive.add(n);
		}
		nonRefOrphaned.addAll(nonRefActive);
		if (!nonRefOrphaned.isEmpty()) {
			Set<KmerEvidence> evidence = evidenceTracker.untrack(nonRefOrphaned.stream().map(n -> new KmerPathSubnode(n)).collect(Collectors.toList()));
			removeFromGraph(evidence);			
			// safety check: did we remove them all?
			for (KmerPathNode n : nonRefOrphaned) {
				if (!n.isValid()) continue;
				if (graphByPosition.contains(n)) {
					log.error("Sanity check failure: %s not removed when clearing orphans (%d evidence found). Attempting to recover by direct node removal ", n, evidence.size(), contigName);
					removeFromGraph(n, true);
				}
			}
		}
	}
	public boolean sanityCheck() {
		graphByKmerNode.entrySet().stream().flatMap(e -> e.getValue().stream()).forEach(kn -> { 
			assert(kn.node().isValid());
			assert(graphByPosition.contains(kn.node()));
		});
		for (KmerPathNode n : graphByPosition) {
			assert(n.isValid());
			assert(evidenceTracker.matchesExpected(new KmerPathSubnode(n)));
		}
		if (Defaults.SANITY_CHECK_MEMOIZATION && MemoizedContigCaller.ASSERT_ALL_OPERATIONS) {
			if (bestContigCaller != null) assert(bestContigCaller.sanityCheck());
			if (bestUnanchoredContigCaller != null) assert(bestUnanchoredContigCaller.sanityCheck());
		}
		return true;
	}
	public boolean sanityCheckDisjointNodeIntervals() {
		Map<Long, List<KmerPathNode>> byKmer = graphByPosition
	            .stream()
	            .collect(Collectors.groupingBy(KmerPathNode::firstKmer));
		for (List<KmerPathNode> list : byKmer.values()) {
			if (list.size() == 1) continue;
			ArrayList<KmerPathNode> al = Lists.newArrayList(list);
			al.sort(KmerNodeUtil.ByFirstStart);
			for (int i = 1; i < al.size(); i++) {
				assert(al.get(i - 1).firstEnd() < al.get(i).firstStart());
			}
		}
		return true;
	}
	public int tracking_activeNodes() {
		return graphByPosition.size();
	}
	public int tracking_maxKmerActiveNodeCount() {
		return graphByKmerNode.values().stream().mapToInt(x -> x.size()).max().orElse(0);
	}
	public long tracking_underlyingConsumed() {
		return consumed;
	}
	public int tracking_inputPosition() {
		return nextPosition();
	}
	public int tracking_firstPosition() {
		if (graphByPosition.size() == 0) return Integer.MAX_VALUE;
		return graphByPosition.first().firstStart();
	}
	public PositionalDeBruijnGraphTracker getExportTracker() {
		return exportTracker;
	}
	public void setExportTracker(PositionalDeBruijnGraphTracker exportTracker) {
		this.exportTracker = exportTracker;
	}
	public ContigStats tracking_lastContig() {
		return stats;
	}
	public int tracking_contigsCalled() {
		return contigsCalled;
	}
}
