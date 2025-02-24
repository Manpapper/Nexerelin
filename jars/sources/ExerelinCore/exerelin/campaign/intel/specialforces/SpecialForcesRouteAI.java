package exerelin.campaign.intel.specialforces;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.inspection.HegemonyInspectionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionIntel;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.impl.campaign.rulecmd.Nex_FactionDirectory;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.campaign.AllianceManager;
import exerelin.campaign.SectorManager;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.fleets.PlayerInSystemTracker;
import exerelin.campaign.intel.colony.ColonyExpeditionIntel;
import exerelin.campaign.intel.fleets.OffensiveFleetIntel;
import exerelin.campaign.intel.invasion.InvasionIntel;
import exerelin.campaign.intel.raid.BaseStrikeIntel;
import exerelin.campaign.intel.raid.NexRaidIntel;
import exerelin.campaign.intel.satbomb.SatBombIntel;
import exerelin.utilities.ExerelinUtilsFaction;
import exerelin.utilities.ExerelinUtilsFleet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * Strategic level AI for special forces fleets. Handles perhaps everything to do with the managed route.
 */
public class SpecialForcesRouteAI {
	
	public static Logger log = Global.getLogger(SpecialForcesRouteAI.class);
	
	public static final float MAX_RAID_ETA_TO_CARE = 60;
	// only defend against player if they're at least this strong
	public static final float MIN_PLAYER_STR_TO_DEFEND = 300;
	public static final float PLAYER_STR_DIVISOR = 400;
	// don't defend against player attacking a location more than this many light-years away
	public static final float MAX_PLAYER_DISTANCE_TO_DEFEND = 10;
	
	// can't be a plain Object because when loaded the one in segment will no longer equal the static value
	public static final String ROUTE_IDLE_SEGMENT = "SF_idleSeg";
	
	protected SpecialForcesIntel sf;
	protected SpecialForcesTask currentTask;
	
	protected IntervalUtil recheckTaskInterval = new IntervalUtil(7, 13);
	
	public SpecialForcesRouteAI(SpecialForcesIntel sf) {
		this.sf = sf;
	}
	
	public TaskType getCurrentTaskType() {
		if (currentTask != null) return currentTask.type;
		return null;
	}
	
	protected List<RaidIntel> getActiveRaids() {
		List<RaidIntel> raids = new ArrayList<>();
		for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel()) 
		{
			if (!(intel instanceof RaidIntel))
				continue;
			
			RaidIntel raid = (RaidIntel)intel;
			
			if (raid.isEnding() || raid.isEnded())
				continue;
			
			raids.add(raid);
		}
		return raids;
	}
	
	// runcode exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.debugMercy();
	public static void debugMercy() {
		for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(OffensiveFleetIntel.class)) {
			OffensiveFleetIntel raid = (OffensiveFleetIntel)intel;
			log.info(raid.getName() + ": " + shouldShowPlayerMercy(raid));
		}
	}
	
	/**
	 * Cut player some slack early on: don't join raids against size 3-4 markets.
	 * (except Starfarer mode)
	 * @param raid
	 * @return
	 */
	public static boolean shouldShowPlayerMercy(RaidIntel raid) {
		if (SectorManager.getHardMode()) return false;
		
		FactionAPI player = Global.getSector().getPlayerFaction();
		if (raid instanceof OffensiveFleetIntel) 
		{
			OffensiveFleetIntel ofi = (OffensiveFleetIntel)raid;
			MarketAPI target = ofi.getTarget();
			if (target != null && target.getFaction() == player
					&& target.getSize() < 5)
				return true;
		} 
		else if (raid instanceof HegemonyInspectionIntel) 
		{
			HegemonyInspectionIntel insp = (HegemonyInspectionIntel)raid;
			if (insp.getTarget().getSize() < 5)
				return true;
		}
		else {
			StarSystemAPI sys = raid.getSystem();
			if (sys != null) 
			{
				MarketAPI owner = ExerelinUtilsFaction.getSystemOwningMarket(sys);
				if (owner != null && owner.getFaction() == player && owner.getSize() < 5)
					return true;
			}
		}
		return false;
	}
	
	protected boolean isAssistableFriendlyRaid(RaidIntel raid) {
		if (!raid.getFaction().equals(sf.faction))
			return false;

		if (raid instanceof OffensiveFleetIntel) {
			if (raid instanceof BaseStrikeIntel) return false;
			if (raid instanceof ColonyExpeditionIntel) return false;
			if (((OffensiveFleetIntel)raid).getOutcome() != null) return false;
		}
		else {	// probably a pirate raid
			if (raid instanceof PunitiveExpeditionIntel) return false;
		}
		if (shouldShowPlayerMercy(raid)) return false;
		
		return true;
	}
	
	/**
	 * Gets active raid-type events by us against hostile factions.
	 * @return
	 */
	protected List<RaidIntel> getActiveRaidsFriendly() {
		List<RaidIntel> raids = getActiveRaids();
		List<RaidIntel> raidsFiltered = new ArrayList<>();
		for (RaidIntel raid : raids) {
			if (!isAssistableFriendlyRaid(raid))
				continue;			
			
			raidsFiltered.add(raid);
		}
		return raidsFiltered;
	}
	
	protected boolean hasMarketInSystem(StarSystemAPI system, FactionAPI faction) 
	{
		if (system == null) return false;
		for (MarketAPI market : Misc.getMarketsInLocation(system))
		{
			if (market.getFaction() == faction)
				return true;
		}
		return false;
	}
	
	protected boolean isDefendableEnemyRaid(RaidIntel raid) {
		if (!raid.getFaction().isHostileTo(sf.faction))
			return false;
		
		if (raid instanceof OffensiveFleetIntel) {
			//sf.debugMsg("Testing raid intel " + raid.getName(), false);
			if (raid instanceof BaseStrikeIntel) return false;
			if (raid instanceof ColonyExpeditionIntel) return false;
			
			// Nex raid, valid if one of the targeted markets is ours
			if (raid instanceof NexRaidIntel) {
				if (hasMarketInSystem(raid.getSystem(), sf.faction))
					return true;
			}
			
			OffensiveFleetIntel ofi = (OffensiveFleetIntel)raid;
			if (ofi.getOutcome() != null) {
				//sf.debugMsg("  Outcome already happened", true);
				return false;
			}
			
			// Only count the raid if we are the target
			FactionAPI targetFaction = ofi.getTarget().getFaction();
			if (targetFaction != sf.faction){
				//sf.debugMsg("  Wrong faction: " + targetFaction.getId() + ", " + sf.faction.getId(), true);
				return false;
			}
			return true;
		}
		else {	// probably a pirate raid
			// Only count the raid if we have a market in target system
			if (hasMarketInSystem(raid.getSystem(), sf.faction))
				return true;			
		}
		return false;
	}
	
	/**
	 * Gets active raid-type events by hostile factions against us.
	 * @return
	 */
	protected List<RaidIntel> getActiveRaidsHostile() {
		List<RaidIntel> raids = getActiveRaids();
		List<RaidIntel> raidsFiltered = new ArrayList<>();
		for (RaidIntel raid : raids) {			
			if (!isDefendableEnemyRaid(raid))
				continue;		
			
			raidsFiltered.add(raid);
		}
		return raidsFiltered;
	}
	
	public void resetRoute(RouteManager.RouteData route) {
		CampaignFleetAPI fleet = route.getActiveFleet();
		if (fleet != null) {
			fleet.clearAssignments();
		}
		route.getSegments().clear();
		route.setCurrent(null);
	}
	
	protected void addIdleSegment(RouteData route, SectorEntityToken destination) {
		RouteManager.RouteSegment idle = new RouteManager.RouteSegment(365, destination);
		idle.custom = ROUTE_IDLE_SEGMENT;
		route.addSegment(idle);
	}
	
	/**
	 * Get a {@code SectorEntityToken} for the task's route segment to originate from.
	 * @return
	 */
	protected SectorEntityToken getRouteFrom() {
		SectorEntityToken from;
		CampaignFleetAPI fleet = sf.route.getActiveFleet();
		if (fleet != null) {
			from = fleet.getContainingLocation().createToken(fleet.getLocation());
		}
		else from = Global.getSector().getHyperspace().createToken(sf.route.getInterpolatedHyperLocation());
		
		return from;
	}
	
	/**
	 * Set task as current, updating routes and the like.
	 * @param task
	 */
	public void assignTask(SpecialForcesTask task) 
	{
		if (task.type == TaskType.IDLE && currentTask != null && currentTask.type == TaskType.IDLE)
			return;
		
		RouteData route = sf.route;
		currentTask = task;
		sf.debugMsg("Assigning task of type " + task.type + "; priority " 
				+ String.format("%.1f", task.priority), false);
		if (task.market != null) 
			sf.debugMsg("  Target: " + task.market.getName(), true);
		else if (task.system != null)
			sf.debugMsg("  Target: " + task.system.getNameWithLowercaseType(), true);
		
		CampaignFleetAPI fleet = route.getActiveFleet();	
		SectorEntityToken from = getRouteFrom();
		
		resetRoute(route);
		
		// get time for assignment, estimate travel time needed
		float travelTime = 0;
		
		// setup a travel segment and an action segment
		RouteManager.RouteSegment actionSeg = null, travelSeg = null;
		SectorEntityToken destination = null;
		switch (task.type) {
			case REBUILD:
			case DEFEND_RAID:
			case DEFEND_VS_PLAYER:
			case ASSIST_RAID:
			case PATROL:
			case RAID:
			case ASSEMBLE:
				destination = task.market == null ? task.system.getCenter() : task.market.getPrimaryEntity();
				travelTime = RouteLocationCalculator.getTravelDays(from, destination);
				sf.debugMsg("Travel time: " + travelTime + "; from " + from.getLocation(), false);
				travelSeg = new RouteManager.RouteSegment(travelTime, from, destination);
				actionSeg = new RouteManager.RouteSegment(task.time, destination);
				break;
			case HUNT_PLAYER:
				// TODO				
			case IDLE:
				// go to nearest star system and just bum around it for a bit
				StarSystemAPI system;
				if (fleet != null) {
					system = route.getActiveFleet().getStarSystem();
					if (system == null)
					{
						system = Misc.getNearestStarSystem(from);
					}
				}
				else {
					system = Misc.getNearestStarSystem(from);
				}
				if (system == null) break;
				destination = system.getCenter();
				
				travelTime = RouteLocationCalculator.getTravelDays(from, destination);
				travelSeg = new RouteManager.RouteSegment(task.time + travelTime, destination);
				actionSeg = new RouteManager.RouteSegment(task.time, destination);
				break;
		}
		
		// if joining a raid, try to make sure we arrive at the same time as them
		// instead of showing up super early and potentially getting whacked
		if (task.type == TaskType.ASSIST_RAID) {
			float delay = task.raid.getETA() - travelTime;
			if (delay > 0) {
				RouteManager.RouteSegment wait = new RouteManager.RouteSegment(delay, from);
				wait.custom = SpecialForcesAssignmentAI.CUSTOM_DELAY_BEFORE_RAID;
				route.addSegment(wait);
			}
		}
		
		// don't have a travel segment if fleet is already in target system
		//if (destination != null && fleet != null && fleet.getContainingLocation() == destination.getContainingLocation())
		//	travelSeg = null;
		
		if (task.type == TaskType.ASSEMBLE)
			travelSeg = null;
		
		if (travelSeg != null) {
			route.addSegment(travelSeg);
		}
		if (actionSeg != null) {
			route.addSegment(actionSeg);
		}
		
		// placeholder to keep the route from expiring
		addIdleSegment(route, destination);
		
		if (fleet != null) {
			fleet.clearAssignments();
		}
		
		sf.sendUpdateIfPlayerHasIntel(SpecialForcesIntel.NEW_ORDERS_UPDATE, false, false);
	}
	
	public SpecialForcesTask generateRaidDefenseTask(RaidIntel raid, float priority) {
		SpecialForcesTask task = new SpecialForcesTask(TaskType.DEFEND_RAID, priority);
		task.raid = raid;
		task.system = task.raid.getSystem();
		if (task.raid instanceof OffensiveFleetIntel) {
			task.market = ((OffensiveFleetIntel)task.raid).getTarget();
		}
		task.time = 30 + raid.getETA();
		return task;
	}
	
	public SpecialForcesTask generateRaidAssistTask(RaidIntel raid, float priority) {
		SpecialForcesTask task = new SpecialForcesTask(TaskType.ASSIST_RAID, priority);
		task.raid = raid;
		task.system = task.raid.getSystem();
		if (task.raid instanceof OffensiveFleetIntel) {
			task.market = ((OffensiveFleetIntel)task.raid).getTarget();
		}
		task.time = 30;	// don't add ETA here, apply it as a delay instead
		return task;
	}
	
	public SpecialForcesTask generatePatrolTask(MarketAPI market, float priority) {
		SpecialForcesTask task = new SpecialForcesTask(TaskType.PATROL, priority);
		task.system = market.getStarSystem();
		task.market = market;
		return task;
	}
	
	public SpecialForcesTask generateDefendVsPlayerTask(LocationAPI loc, float priority) {
		SpecialForcesTask task = new SpecialForcesTask(TaskType.DEFEND_VS_PLAYER, priority);
		
		task.market = getLargestMarketInSystem(loc, sf.faction);
		task.system = task.market.getStarSystem();
		
		return task;
	}
	
	public static MarketAPI getLargestMarketInSystem(LocationAPI loc, FactionAPI faction) {
		List<MarketAPI> all = Global.getSector().getEconomy().getMarkets(loc);
		if (all.isEmpty()) return null;
		MarketAPI largest = all.get(0);
		int largestSize = 0;
		
		for (MarketAPI market : all)
		{
			if (market.getFaction() != faction)
				continue;
			int size = market.getSize();
			if (size > largestSize) {
				largest = market;
				largestSize = size;
			}
		}
		return largest;
	}
	
	/**
	 * Picks a task for the task force to do.
	 * @param priorityDefenseOnly Only check for any urgent defense tasks that 
	 * should take priority over what we're currently doing.
	 * @return 
	 */
	public SpecialForcesTask pickTask(boolean priorityDefenseOnly) 
	{
		sf.debugMsg("Picking task for " + sf.getFleetNameForDebugging(), false);
		
		boolean isBusy = currentTask != null && currentTask.type.isBusyTask();
		
		// check for priority defense against player operating in one of our systems
		if (getCurrentTaskType() != TaskType.DEFEND_VS_PLAYER) {
			LocationAPI loc = Global.getSector().getPlayerFleet().getContainingLocation();
			float priority = getDefendVsPlayerPriority(loc);
			float toBeat = currentTask != null ? currentTask.priority : 0;
			if (isBusy) toBeat *= 2;
			//sf.debugMsg("Priority defense task comparison: " + priority + " / " + toBeat, false);
			
			if (toBeat < priority) {
				SpecialForcesTask task = generateDefendVsPlayerTask(loc, priority);
				return task;
			}
		}
		
		// check for priority raid defense missions
		List<Pair<RaidIntel, Float>> hostileRaids = new ArrayList<>();
		for (RaidIntel raid : getActiveRaidsHostile()) {
			if (raid.getETA() > MAX_RAID_ETA_TO_CARE) continue;
			hostileRaids.add(new Pair<>(raid, getRaidDefendPriority(raid)));
		}
		//sf.debugMsg("Hostile raid count: " + hostileRaids.size(), false);
		
		Pair<RaidIntel, Float> priorityDefense = pickPriorityDefendTask(hostileRaids, isBusy);
		if (priorityDefense != null) {
			SpecialForcesTask task = generateRaidDefenseTask(priorityDefense.one, priorityDefense.two);
			return task;
		}
				
		// no high priority defense, look for another task
		if (priorityDefenseOnly)
			return null;
		
		WeightedRandomPicker<SpecialForcesTask> picker = new WeightedRandomPicker<>();
		
		// Defend vs. raid
		for (Pair<RaidIntel, Float> raid : hostileRaids) {
			picker.add(generateRaidDefenseTask(raid.one, raid.two), raid.two);
		}
		
		// Assist raid
		for (RaidIntel raid : getActiveRaidsFriendly()) {
			if (raid.getETA() > MAX_RAID_ETA_TO_CARE) continue;
			float priority = getRaidAttackPriority(raid);
			picker.add(generateRaidAssistTask(raid, priority), priority);
		}
		
		// Patrol
		List<MarketAPI> alliedMarkets = getAlliedMarkets();
		int numMarkets = alliedMarkets.size();
		for (MarketAPI market : alliedMarkets) {
			float priority = getPatrolPriority(market);
			priority /= numMarkets;
			picker.add(generatePatrolTask(market, priority), priority);
		}
		
		// idle
		if (picker.isEmpty()) {
			SpecialForcesTask task = new SpecialForcesTask(TaskType.IDLE, 0);
			task.time = 15;
			return task;
		}
		
		return picker.pick();
	}
	
	public List<MarketAPI> getAlliedMarkets() {
		String factionId = sf.faction.getId();
		List<MarketAPI> alliedMarkets;
		if (AllianceManager.getFactionAlliance(factionId) != null) {
			alliedMarkets = AllianceManager.getFactionAlliance(factionId).getAllianceMarkets();
		}
		else
			alliedMarkets = ExerelinUtilsFaction.getFactionMarkets(factionId);
		
		return alliedMarkets;
	}
	
	/**
	 * Picks the highest-priority raid for a priority defense assignment, if any exceed the needed priority threshold.
	 * If no raid is picked, the task force may still randomly pick a raid to defend against.
	 * @param raids
	 * @param isBusy
	 * @return
	 */
	protected Pair<RaidIntel, Float> pickPriorityDefendTask(List<Pair<RaidIntel, Float>> raids, boolean isBusy) 
	{
		Pair<RaidIntel, Float> highest = null;
		float highestScore = currentTask != null ? currentTask.priority : 0;
		if (isBusy) highestScore *= 2;
		float minimum = getPriorityNeededForUrgentDefense(isBusy);
		
		for (Pair<RaidIntel, Float> entry : raids) {
			float score = entry.two;
			if (score < minimum) continue;
			if (score > highestScore) {
				highestScore = score;
				highest = entry;
			}
		}
		
		return highest;
	}
	
	protected boolean wantNewTask() {
		TaskType taskType = currentTask == null ? TaskType.IDLE : currentTask.type;
		
		if (taskType == TaskType.REBUILD || taskType == TaskType.ASSEMBLE)
			return false;
		
		boolean wantNewTask = false;
		
		if (taskType == TaskType.IDLE) {
			return true;
		}
		// We were assigned to assist or defend against a raid, but it's already ended
		// or otherwise no longer applicable
		else if (currentTask.raid != null) {
			RaidIntel raid = currentTask.raid;
			if (raid.isEnding() || raid.isEnded()) {
				return true;
			}
			else if (taskType == TaskType.ASSIST_RAID && !isAssistableFriendlyRaid(raid)) 
			{
				return true;
			}
			else if (taskType == TaskType.DEFEND_RAID && !isDefendableEnemyRaid(raid))
			{
				return true;
			}
		}
		// defending vs. player in system, but player already left
		else if (taskType == TaskType.DEFEND_VS_PLAYER 
				&& Global.getSector().getPlayerFleet().getContainingLocation() != currentTask.system) 
		{
			return true;
		}
		
		return false;
	}
	
	/**
	 * Check if we should be doing something else.
	 */
	public void updateTaskIfNeeded() 
	{
		if (sf.route.getActiveFleet() != null && sf.route.getActiveFleet().getBattle() != null)
			return;
		
		//sf.debugMsg("Checking " + sf.getFleetNameForDebugging() + " for task change", false);
		boolean wantNewTask = wantNewTask();
		
		// don't want a new task, but check if there's a defend task we should divert to
		if (!wantNewTask)
		{
			TaskType taskType = currentTask == null ? null : currentTask.type;
			
			if (taskType == TaskType.RAID 
				|| taskType == TaskType.ASSIST_RAID
				|| taskType == TaskType.DEFEND_VS_PLAYER
				|| taskType == TaskType.PATROL) 
			{
				SpecialForcesTask task = pickTask(true);
				if (task != null) {
					assignTask(task);
					return;
				}
			}
		}
		// want a new task
		else {
			SpecialForcesTask task = pickTask(false);
			if (task != null) {
				assignTask(task);
			}
		}
	}
	
	/**
	 * Are we actually close enough to the target entity to execute the ordered task?
	 * @return
	 */
	public boolean isCloseEnoughForTask() {
		CampaignFleetAPI fleet = sf.route.getActiveFleet();
		if (fleet == null) return true;
		
		SectorEntityToken target = currentTask.market.getPrimaryEntity();
		return MathUtils.getDistance(fleet, target) < 250;	// FIXME
	}
	
	public void notifyRouteFinished() {
		sf.debugMsg("Route finished, looking for new task", false);
		Vector2f currLoc = sf.route.getInterpolatedHyperLocation();
		
		if (currentTask == null) {
			pickTask(false);
			return;
		}
		
		if (currentTask.type == TaskType.REBUILD) 
		{
			sf.debugMsg("Attempting to rebuild fleet " + sf.getFleetNameForDebugging(), false);
			// Not close enough, wait a while longer
			if (!isCloseEnoughForTask()) {
				sf.debugMsg("Not close enough, retrying", true);
				sf.route.getSegments().clear();
				sf.route.setCurrent(null);
				sf.route.addSegment(new RouteManager.RouteSegment(currentTask.time * 0.5f, 
						currentTask.market.getPrimaryEntity()));
				addIdleSegment(sf.route, currentTask.market.getPrimaryEntity());
				return;
			}
			
			sf.executeRebuildOrder(currentTask.market);
			// spend a few days orbiting the planet, to shake down the new members
			SpecialForcesTask task = new SpecialForcesTask(TaskType.ASSEMBLE, 100);
			task.market = currentTask.market;
			task.time = 2;
			assignTask(task);
			return;
		}
		
		currentTask = null;
		pickTask(false);
	}
	
	public void advance(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);
		recheckTaskInterval.advance(amount);
		if (recheckTaskInterval.intervalElapsed()) {
			updateTaskIfNeeded();
		}
	}
	
	/**
	 * Gets the priority level for defending against the specified raid-type event.
	 * @param raid
	 * @return
	 */
	public float getRaidDefendPriority(RaidIntel raid) {
		List<MarketAPI> targets = new ArrayList<>();
		float mult = 1;
		
		if (raid instanceof OffensiveFleetIntel) {
			OffensiveFleetIntel ofi = (OffensiveFleetIntel)raid;
			
			// raid: assign values for all allied markets contained in system
			if (raid instanceof NexRaidIntel) {
				for (MarketAPI market : Global.getSector().getEconomy().getMarkets(ofi.getTarget().getContainingLocation()))
				{
					if (!AllianceManager.areFactionsAllied(market.getFactionId(), sf.faction.getId()))
						continue;
					targets.add(market);
				}
			}
			else {
				targets.add(ofi.getTarget());
			}
			
			if (raid instanceof InvasionIntel)
				mult = 6;
			else if (raid instanceof SatBombIntel)
				mult = 8;
		}
		
		float priority = 0;
		for (MarketAPI market : targets) {
			priority += getRaidDefendPriority(market);
		}
		priority *= mult;
		
		return priority;
	}
	
	/**
	 * Gets the priority level for assisting the specified raid-type event.
	 * @param raid
	 * @return
	 */
	public float getRaidAttackPriority(RaidIntel raid) {
		List<MarketAPI> targets = new ArrayList<>();
		float mult = 1;
		
		if (raid instanceof OffensiveFleetIntel) {
			OffensiveFleetIntel ofi = (OffensiveFleetIntel)raid;
			
			// raid: assign values for all hostile markets contained in system
			if (raid instanceof NexRaidIntel) {
				for (MarketAPI market : Global.getSector().getEconomy().getMarkets(ofi.getTarget().getContainingLocation()))
				{
					if (market.getFaction().isHostileTo(sf.faction))
						continue;
					targets.add(market);
				}
			}
			else {
				targets.add(ofi.getTarget());
			}
			
			if (raid instanceof InvasionIntel)
				mult = 3;
			else if (raid instanceof SatBombIntel)
				mult = 3;
		}
		
		float priority = 0;
		for (MarketAPI market : targets) {
			priority += getRaidAttackPriority(market);
		}
		priority *= mult;
		
		return priority;
	}
	
	/**
	 * Gets the priority for defending the specified market against a raid-type event.
	 * @param market
	 * @return
	 */
	public float getRaidDefendPriority(MarketAPI market) {
		float priority = market.getSize() * market.getSize();
		if (Nex_FactionDirectory.hasHeavyIndustry(market))
			priority *= 4;
		
		sf.debugMsg("  Defending market " + market.getName() + " has priority " + String.format("%.1f", priority), true);
		return priority;
	}
	
	/**
	 * Gets the priority for attacking the specified market during a raid-type event.
	 * @param market
	 * @return
	 */
	public float getRaidAttackPriority(MarketAPI market) {
		float priority = market.getSize() * market.getSize();
		if (Nex_FactionDirectory.hasHeavyIndustry(market))
			priority *= 3;
		
		sf.debugMsg("  Attacking market " + market.getName() + " has priority " + String.format("%.1f", priority), true);
		return priority;
	}
	
	/**
	 * Gets the priority for patrolling this market in the absence of raiding activity.
	 * @param market
	 * @return
	 */
	public float getPatrolPriority(MarketAPI market) {
		float priority = market.getSize() * market.getSize();
		if (Nex_FactionDirectory.hasHeavyIndustry(market))
			priority *= 4;
		if (market.getFaction() != sf.faction)	// lower priority for allies' markets
			priority *= 0.75f;
		
		// TODO: include term for distance
		
		// pirate, Stormhawk, etc. activity
		if (market.hasCondition(Conditions.PIRATE_ACTIVITY))
			priority *= 2;
		if (market.hasCondition("vayra_raider_activity"))
			priority *= 2;
		
		// high interest in patrolling locations where a hostile player is
		if (Global.getSector().getPlayerFaction().isHostileTo(sf.faction) 
				&& Global.getSector().getPlayerFleet().getContainingLocation() == market.getContainingLocation())
			priority *= 3;
		
		float def = InvasionFleetManager.estimatePatrolStrength(market, 0.5f) 
				+ InvasionFleetManager.estimateStationStrength(market);
		priority *= 100/def;
		sf.debugMsg("  Patrolling market " + market.getName() + " has priority " 
				+ String.format("%.1f", priority) + "; defensive rating " + String.format("%.1f", def), true);
		return priority;
	}
	
	/**
	 * Gets the priority for defending one of our star systems if player is present.
	 * @param loc
	 * @return
	 */
	public float getDefendVsPlayerPriority(LocationAPI loc) {
		if (loc.isHyperspace()) return 0;
		if (!sf.faction.isHostileTo(Factions.PLAYER))
			return -1;
		if (!PlayerInSystemTracker.hasFactionSeenPlayer(loc, sf.faction.getId()))
			return -1;
		
		float playerStr = ExerelinUtilsFleet.calculatePowerLevel(Global.getSector().getPlayerFleet());
		if (playerStr < MIN_PLAYER_STR_TO_DEFEND) return 0;
		
		// don't bother if too far away
		float distLY = Misc.getDistanceLY(loc.getLocation(), getRouteFrom().getLocationInHyperspace());
		if (distLY > MAX_PLAYER_DISTANCE_TO_DEFEND) return 0;
		
		float priority = 0;
		for (MarketAPI market : Global.getSector().getEconomy().getMarkets(loc))
		{
			if (market.getFaction() != sf.faction) continue;
			priority += getPatrolPriority(market);
		}
		priority *= playerStr / PLAYER_STR_DIVISOR;
		
		sf.debugMsg("  Defending " + loc.getName() + " from player has priority " 
				+ String.format("%.1f", priority), false);
		return priority;
	}
	
	/**
	 * A raid must have at least this much defend priority for the special forces unit to be tasked
	 * to defend against it. Required priority will be increased if we already have another task.
	 * @param isBusy True if we're checking whether to cancel an existing busy-type assignment.
	 * @return
	 */
	public float getPriorityNeededForUrgentDefense(boolean isBusy) {
		int aggro = sf.faction.getDoctrine().getAggression();
		switch (aggro) {
			case 5:
				if (isBusy) return 9999999;
				return 50;
			default:
				return 8 * aggro * (isBusy ? 2 : 1);
		}
	}
	
	public void addInitialTask() {
		float orbitDays = 1 + sf.startingFP * 0.02f * (0.75f + (float) Math.random() * 0.5f);
		
		SpecialForcesTask task = new SpecialForcesTask(TaskType.ASSEMBLE, 100);
		task.market = sf.origin;
		task.time = orbitDays;
		assignTask(task);
	}
	
	public enum TaskType {
		RAID, PATROL, ASSIST_RAID, DEFEND_RAID, REBUILD, 
		DEFEND_VS_PLAYER, HUNT_PLAYER, ASSEMBLE, IDLE;
		
		/**
		 * Returns true for tasks we don't like to "put down", 
		 * i.e. reassignment would be considered inconvenient.
		 * @return
		 */
		public boolean isBusyTask() {
			return this == RAID || this == ASSIST_RAID || this == DEFEND_RAID
					|| this == DEFEND_VS_PLAYER;
		}
	}
	
	public static class SpecialForcesTask {
		public TaskType type;
		public float priority;
		public RaidIntel raid;
		public float time = 45;	// controls how long the action segment lasts
		public MarketAPI market;
		public StarSystemAPI system;
		public Map<String, Object> params = new HashMap<>();
		
		public SpecialForcesTask(TaskType type, float priority) {
			this.type = type;
			this.priority = priority;
		}
		
		/**
		 * Returns a string describing the task.
		 * @return
		 */
		public String getText() {
			switch (type) {
				case RAID:
					return "raiding " + market.getName();
				case ASSIST_RAID:
					return "assisting " + raid.getName();
				case DEFEND_RAID:
					return "defending vs. " + raid.getName();
				case PATROL:
					return "patrolling " + (market != null? market.getName() : system.getNameWithLowercaseType());
				case DEFEND_VS_PLAYER:
					return "defending vs. player in " + Global.getSector().getPlayerFleet().getContainingLocation().getName();
				case REBUILD:
					return "reconstituting fleet at " + market.getName();
				case ASSEMBLE:
					return "assembling at " + market.getName();
				case IDLE:
					return "idle";
				default:
					return "unknown";
			}
		}
	}
}
