package exerelin.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import exerelin.campaign.AllianceManager.Alliance;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;
import exerelin.utilities.ExerelinUtilsFaction;
import java.util.HashSet;
import java.util.Set;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;

/**
 * Handles voting by an alliance's members to join one of their own in war or peace
 */
public class AllianceVoter {
	
	public static final float BASE_POINTS = 75;
	public static final float POINTS_NEEDED = 100;
	public static final float ABSTAIN_THRESHOLD = 25;
	public static final float STRENGTH_POINT_MULT = 0.5f;
	public static final float HAWKISHNESS_POINTS = 50;
	public static final float RANDOM_POINTS = 50;
	
	protected static final boolean DEBUG_LOGGING = true;
	
	public static Logger log = Global.getLogger(AllianceVoter.class);
	
	/**
	 * Decide whether the alliance(s) should declare war on/make peace with the other party, e.g. after a diplomacy event
	 * i.e. if faction1 has declared war on or made peace with faction2, decide whether their alliance(s) should do the same
	 * If either or both vote yes, modify relationships as appropriate
	 * 
	 * If one alliance votes yes but other does not, only declare on/make peace with the one faction involved
	 * @param faction1Id
	 * @param faction2Id
	 * @param isWar True if the two factions have gone to war, false if they are making peace
	 */
	public static void allianceVote(String faction1Id, String faction2Id, boolean isWar)
	{
		Alliance ally1 = AllianceManager.getFactionAlliance(faction1Id);
		Alliance ally2 = AllianceManager.getFactionAlliance(faction2Id);
		if (ally1 == null && ally2 == null) return;
		if (ally1 == ally2) return;
		
		VoteResult vote1 = null, vote2 = null;
		if (ally1 != null) vote1 = allianceVote(ally1, faction1Id, faction2Id, isWar);		
		if (ally2 != null) vote2 = allianceVote(ally2, faction2Id, faction1Id, isWar);
		
		// handle vote results
		if (vote1 != null && vote1.success)
		{
			if (vote2 != null && vote2.success)
				AllianceManager.doAlliancePeaceStateChange(faction1Id, faction2Id, ally1, ally2, isWar);
			else
				AllianceManager.doAlliancePeaceStateChange(faction1Id, faction2Id, ally1, null, isWar);
		}
		else if (vote2 != null && vote2.success)
		{
			AllianceManager.doAlliancePeaceStateChange(faction1Id, faction2Id, null, ally2, isWar);
		}
		// TODO: make event
		// TODO: in future iterations, some factions may disregard the voted decision
	}
	
	/**
	 * Decide whether the alliance should declare war on/make peace with the other faction (and possibly their allies, if any)
	 * @param alliance
	 * @param factionId The alliance member whose action triggered the vote
	 * @param otherFactionId The other faction with whom the alliance member interacted with
	 * @param isWar True if the two factions have gone to war, false if they are making peace
	 * @return True if the alliance votes to also declare war on/make peace with otherFaction, as faction has done
	 */
	protected static VoteResult allianceVote(Alliance alliance, String factionId, String otherFactionId, boolean isWar)
	{
		Set<String> factionsToConsider = new HashSet<>();
		Alliance otherAlliance = AllianceManager.getFactionAlliance(otherFactionId);
		float ourStrength = alliance.getAllianceMarketSizeSum();
		float theirStrength = 0;
		Set<String> yesVotes = new HashSet<>();
		Set<String> noVotes = new HashSet<>();
		Set<String> abstentions = new HashSet<>();
		
		if (otherAlliance != null)
		{
			factionsToConsider.addAll(otherAlliance.members);
			theirStrength = otherAlliance.getAllianceMarketSizeSum();
		}
		else {
			theirStrength = ExerelinUtilsFaction.getFactionMarketSizeSum(otherFactionId);
		}
		if (theirStrength <= 0) theirStrength = 1;
		float strengthRatio = ourStrength / theirStrength;
		
		if (DEBUG_LOGGING)
		{
			String header = "== Vote for peace with ";
			if (isWar) header = "== Vote for war with ";
			
			header += otherFactionId + " ==";
			log.info(header);
		}
		
		for (String allianceMember : alliance.members)
		{
			Vote vote = factionVote(isWar, alliance, allianceMember, factionId, otherFactionId, 
					factionsToConsider, strengthRatio);
			if (vote == Vote.YES)
			{
				yesVotes.add(allianceMember);
			}
			else if (vote == Vote.NO)
			{
				noVotes.add(allianceMember);
			}
		}
		
		boolean success = yesVotes.size() > noVotes.size();
		if (DEBUG_LOGGING)
		{
			log.info("Final vote: " + success + " (" + yesVotes.size() + " to " + noVotes.size() + ")");
		}
		return new VoteResult(success, yesVotes, noVotes, abstentions, new HashSet<String>());
	}
	
	/**
	 * Vote for whether our alliance should join
	 * @param isWar
	 * @param alliance
	 * @param factionId The voting faction
	 * @param friendId The alliance member whose action triggered the vote
	 * @param otherFactionId The other faction with whom the alliance member interacted with
	 * @param otherAllianceMembers Other members of the other faction's alliance, if any
	 * @param strengthRatio Ratio of the sum of market sizes for each side
	 * @return A yes vote if the faction wants to join its ally in war/peace, or no if it does not; abstain if undecided
	 */
	protected static Vote factionVote(boolean isWar, Alliance alliance, String factionId, String friendId, 
			String otherFactionId, Set<String> otherAllianceMembers, float strengthRatio)
	{
		/*
		Stuff that goes into the vote:
		- How much we like our friend
		- How much we like the other faction being interacted with
		- How much we like the other faction's allies in general
		- Our strength versus theirs
		- How aggressive we are in general
		- Random factor
		*/
		
		// if we're the friend, auto-vote yes
		if (factionId.equals(friendId)) return Vote.YES;	
		
		FactionAPI us = Global.getSector().getFaction(factionId);
		ExerelinFactionConfig usConf = ExerelinConfig.getExerelinFactionConfig(factionId);
		
		// don't bother calculating vote if we like/hate them forever
		if (isWar)
		{
			if (ExerelinFactionConfig.getMinRelationship(factionId, otherFactionId) > AllianceManager.HOSTILE_THRESHOLD)
				return Vote.NO;
		}
		else
		{
			if (ExerelinFactionConfig.getMaxRelationship(factionId, otherFactionId) < AllianceManager.HOSTILE_THRESHOLD)
				return Vote.NO;
		}
		
		float friendRelationship = us.getRelationship(friendId);
		float otherRelationship = us.getRelationship(otherFactionId);
				
		int factionCount = 0;
		float otherAllianceRelationshipSum = 0;
		
		float hawkishness = usConf.alignments.get(AllianceManager.Alignment.MILITARIST);
		float diplomaticness = usConf.alignments.get(AllianceManager.Alignment.DIPLOMATIC);
		
		for (String otherMember: otherAllianceMembers)
		{
			if (otherMember.equals(otherFactionId)) continue;
			otherAllianceRelationshipSum += us.getRelationship(otherMember);
			factionCount++;
		}
		
		// from relationships
		float totalPoints = BASE_POINTS + friendRelationship*100;
		float otherRelationshipPoints = isWar ? -(otherRelationship + 0.25f)*100 : (otherRelationship + 0.25f)*100;
		totalPoints += otherRelationshipPoints;
		
		float otherAllianceRelationshipAvg = (factionCount > 0) ? otherAllianceRelationshipSum/factionCount : 0;
		float otherAllianceRelationshipPoints = isWar ? -(otherAllianceRelationshipAvg + 0.25f)*50 
				: (otherAllianceRelationshipAvg + 0.25f)*50;
		totalPoints += otherAllianceRelationshipPoints;
		
		// strength
		float strengthPoints = (strengthRatio - 1) * 100 * STRENGTH_POINT_MULT;
		if (!isWar) strengthPoints *= -1;	// favour peace if we're weaker than them, war if we're stronger
		totalPoints += strengthPoints;
		
		// hawkishness
		float hawkPoints = (hawkishness - diplomaticness) * HAWKISHNESS_POINTS;
		if (!isWar) hawkPoints *= -1;
		totalPoints += hawkPoints;
		
		// random
		float randomPoints = RANDOM_POINTS * (MathUtils.getRandomNumberInRange(-0.5f, 0.5f) + MathUtils.getRandomNumberInRange(-0.5f, 0.5f));
		totalPoints += randomPoints;
		
		Vote vote = Vote.ABSTAIN;
		if (totalPoints > POINTS_NEEDED + ABSTAIN_THRESHOLD)
		{
			vote = Vote.YES;
		}
		else if (totalPoints < POINTS_NEEDED - ABSTAIN_THRESHOLD)
		{
			vote = Vote.NO;
		}
		
		// debug
		if (DEBUG_LOGGING)
		{
			log.info(us.getDisplayName() + " votes: " + vote);
			log.info("\tTotal points: " + totalPoints);
			log.info("\tFriend relationship: " + friendRelationship * 100);
			log.info("\tOther relationship: " + otherRelationshipPoints);
			log.info("\tOther alliance relationship: " + otherAllianceRelationshipPoints);
			log.info("\tStrength points: " + strengthPoints);
			log.info("\tHawk/dove points: " + hawkPoints);
			log.info("\tRandom points: " + randomPoints);
		}
		
		return vote;
	}
	
	public static class VoteResult {
		public final boolean success;
		public final Set<String> yesVotes;
		public final Set<String> noVotes;
		public final Set<String> abstentions;
		public final Set<String> defiedResults;
		
		public VoteResult(boolean success, Set<String> yesVotes, Set<String> noVotes, 
				Set<String> abstentions, Set<String> defiedResults)
		{
			this.success = success;
			this.yesVotes = yesVotes;
			this.noVotes = noVotes;
			this.abstentions = abstentions;
			this.defiedResults = defiedResults;
		}
	}
	
	public enum Vote {
		YES,
		NO,
		ABSTAIN,
		NO_VOTE,
	}
}
