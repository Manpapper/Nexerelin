package exerelin.world;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.CoreCampaignPluginImpl;
import com.fs.starfarer.api.impl.campaign.CoreScript;
import com.fs.starfarer.api.impl.campaign.events.CoreEventProbabilityManager;
import com.fs.starfarer.api.impl.campaign.fleets.DisposableLuddicPathFleetManager;
import com.fs.starfarer.api.impl.campaign.fleets.DisposablePirateFleetManager;
import com.fs.starfarer.api.impl.campaign.fleets.EconomyFleetRouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.MercFleetManagerV2;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.ConstellationGen;
import static com.fs.starfarer.api.impl.campaign.procgen.ConstellationGen.createSpringSystem;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.ProcgenUsedNames;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.CustomConstellationParams;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin;
import com.fs.starfarer.api.util.Misc;
import data.scripts.world.templars.TEM_Antioch;
import exerelin.ExerelinConstants;
import exerelin.campaign.ColonyManager;
import exerelin.plugins.*;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.DiplomacyManager;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.SectorManager;
import exerelin.campaign.StatsTracker;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;
import exerelin.utilities.ExerelinUtils;
import exerelin.utilities.ExerelinUtilsAstro;
import exerelin.utilities.ExerelinUtilsFaction;
import exerelin.utilities.ExerelinUtilsMarket;
import exerelin.utilities.StringHelper;
import java.util.Random;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

@SuppressWarnings("unchecked")
public class ExerelinNewGameSetup implements SectorGeneratorPlugin
{
	//protected float numOmnifacs = 0;
	public static final Vector2f SECTOR_CENTER = new Vector2f(-4000, -6000);
	public static final Vector2f PRISM_LOC = new Vector2f(-8005, -3785);
	public static Logger log = Global.getLogger(ExerelinNewGameSetup.class);
	
	protected Random rand = StarSystemGenerator.random;
	
	// runcode new exerelin.world.ExerelinNewGameSetup().addPrismMarket(Global.getSector(), false);
	public SectorEntityToken addPrismMarket(SectorAPI sector, boolean newGame)
	{
		SectorEntityToken prismEntity;
		
		if (ExerelinSetupData.getInstance().numSystems == 1)
		{
			// FIXME 
			SectorEntityToken toOrbit = Global.getSector().getEntityById("jangala");	//null;
			float radius = toOrbit.getRadius();
			float orbitDistance = radius + 150;
			if (toOrbit instanceof PlanetAPI)
			{
				PlanetAPI planet = (PlanetAPI)toOrbit;
				if (planet.isStar()) 
				{
					orbitDistance = radius + 2000 + rand.nextFloat() * 500;
				}
			}
			prismEntity = toOrbit.getContainingLocation().addCustomEntity("nex_prismFreeport", "Prism Freeport", "exerelin_freeport_type", "independent");
			prismEntity.setCircularOrbitPointingDown(toOrbit, ExerelinUtilsAstro.getRandomAngle(rand), orbitDistance, ExerelinUtilsAstro.getOrbitalPeriod(toOrbit, orbitDistance));
		}
		else if (newGame && !ExerelinConfig.prismInHyperspace)
		{
			prismEntity = generatePrismInOwnSystem();
		}
		else
		{
			LocationAPI hyperspace = sector.getHyperspace();
			prismEntity = hyperspace.addCustomEntity("nex_prismFreeport", "Prism Freeport", "exerelin_freeport_type", "independent");
			prismEntity.setCircularOrbitWithSpin(hyperspace.createToken(PRISM_LOC), ExerelinUtilsAstro.getRandomAngle(rand), 150, 60, 30, 30);
			clearDeepHyper(prismEntity, 400);
		}
		
		prismEntity.addTag(ExerelinConstants.TAG_UNINVADABLE);
		
		/*
		EntityData data = new EntityData(null);
		data.name = "Prism Freeport";
		data.type = EntityType.STATION;
		data.forceMarketSize = 4;
		
		MarketAPI market = addMarketToEntity(prismEntity, data, "independent");
		*/

		MarketAPI market = Global.getFactory().createMarket("nex_prismFreeport" /*+ "_market"*/, "Prism Freeport", 5);
		market.setFactionId(Factions.INDEPENDENT);
		market.addCondition(Conditions.POPULATION_5);
		market.addIndustry(Industries.POPULATION);
		market.addIndustry("commerce");
		market.addIndustry(Industries.LIGHTINDUSTRY);
		//market.addIndustry(Industries.MILITARYBASE);
		market.addIndustry(Industries.PATROLHQ);
		market.addIndustry(Industries.MEGAPORT);
		//market.addIndustry(Industries.HEAVYINDUSTRY);
		market.addIndustry(Industries.WAYSTATION);
		market.addIndustry(Industries.HEAVYBATTERIES);
		market.addIndustry(Industries.STARFORTRESS_HIGH);	// Arrays.asList(new String[]{Commodities.ALPHA_CORE}));
		//market.addIndustry(Industries.CRYOSANCTUM);
		
		market.setFreePort(true);
		market.addSubmarket(Submarkets.SUBMARKET_OPEN);
		//market.addSubmarket(Submarkets.GENERIC_MILITARY);
		market.addSubmarket(Submarkets.SUBMARKET_BLACK);
		market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
		
		market.getMemoryWithoutUpdate().set(ExerelinConstants.MEMORY_KEY_UNINVADABLE, true);
		
		market.getTariff().modifyFlat("generator", sector.getFaction(Factions.INDEPENDENT).getTariffFraction());
		ExerelinUtilsMarket.setTariffs(market);
		market.addSubmarket("exerelin_prismMarket");
		market.setPrimaryEntity(prismEntity);
		prismEntity.setMarket(market);
		prismEntity.setFaction(Factions.INDEPENDENT);
		market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);	// not doing this makes market condition tooltips fail to appear
		sector.getEconomy().addMarket(market, true);
		
		//pickEntityInteractionImage(prismEntity, market, "", EntityType.STATION);
		//prismEntity.setInteractionImage("illustrations", "space_bar");
		prismEntity.setCustomDescriptionId("exerelin_prismFreeport");
		
		
		// add important people
		if (!newGame) 
		{
			ExerelinUtilsMarket.addPerson(Global.getSector().getImportantPeople(), 
					market, Ranks.SPACE_CAPTAIN, Ranks.POST_STATION_COMMANDER, true);
			ExerelinUtilsMarket.addPerson(Global.getSector().getImportantPeople(), 
					market, Ranks.CITIZEN, Ranks.POST_PORTMASTER, true);
			ExerelinUtilsMarket.addPerson(Global.getSector().getImportantPeople(), 
					market, Ranks.SPACE_COMMANDER, Ranks.POST_SUPPLY_OFFICER, true);
			ColonyManager.reassignAdminIfNeeded(market, market.getFaction(), market.getFaction());
		}
		
		return prismEntity;
	}
	
	public SectorEntityToken generatePrismInOwnSystem() {
		int dist = 1050;
		int orbitPeriod = 361;
		
		String name = StringHelper.getString("nex_world", "prismSystem_name");
		StarSystemAPI system = Global.getSector().createStarSystem(name);
		system.setAge(StarAge.YOUNG);
		system.getLocation().set(PRISM_LOC);
		ProcgenUsedNames.notifyUsed(name);
		system.setType(StarSystemGenerator.StarSystemType.NEBULA);
		
		// temporarily create a "star"
		PlanetAPI star = system.initStar("nex_prism_center", "nebula_center_young", 0, 0);
		star.setSkipForJumpPointAutoGen(true);
		star.addTag(Tags.AMBIENT_LS);
		StarSystemGenerator.addSystemwideNebula(system, StarAge.YOUNG);
		
		// jump point
		JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("nex_prism_jump", 
				StringHelper.getString("nex_world", "prismSystem_jumpName"));
		jumpPoint.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint);
		
		// remove the star
		system.removeEntity(star);
		StarCoronaTerrainPlugin coronaPlugin = Misc.getCoronaFor(star);
		if (coronaPlugin != null) {
			system.removeEntity(coronaPlugin.getEntity());
		}
		system.setStar(null);
		SectorEntityToken center = system.initNonStarCenter();
		center.addTag(Tags.AMBIENT_LS);
		jumpPoint.setCircularOrbit(center, 0, dist, orbitPeriod);
		
		system.autogenerateHyperspaceJumpPoints(true, false);
		
		system.setStar(star);
		
		// comm relay
		SectorEntityToken relay = system.addCustomEntity("nex_prism_relay", null, 
				Entities.COMM_RELAY_MAKESHIFT, Factions.INDEPENDENT);
		int period2 = (int)Math.round(Math.pow(2, 3/2) * orbitPeriod);
		relay.setCircularOrbitPointingDown(star, 105, dist*2, period2);
		
		// station
		name = StringHelper.getString("nex_world", "prismSystem_stationName");
		SectorEntityToken prism = system.addCustomEntity("nex_prismFreeport", name, "exerelin_freeport_type", Factions.INDEPENDENT);
		prism.setCircularOrbitWithSpin(center, 240, dist, orbitPeriod, 30, 30);
		
		return prism;
	}
	
	public void validateLocation(SectorAPI sector, StarSystemAPI system, Vector2f startLoc) 
	{
		Random random = new Random(ExerelinUtils.getStartingSeed());
		float minDist = 100;
		try_again:
		for (int i = 0; i < 100; i++) {
			Vector2f loc = new Vector2f(startLoc);
			if (i > 0) {
				Vector2f mod = new Vector2f(0, 120*i);
				mod = VectorUtils.rotate(mod, random.nextFloat() * 360);
				loc.x += mod.x;
				loc.y += mod.y;
			}
			
			for (StarSystemAPI sys : sector.getStarSystems()) {
				float otherRadius = sys.getMaxRadiusInHyperspace();
				if (MathUtils.getDistance(sys.getLocation(), loc) < minDist + otherRadius) {
					log.info("Prism invalid location attempt " + i);
					continue try_again;
				}
			}
			system.getLocation().set(loc.x, loc.y);
			break;
		}
		
		clearDeepHyper(system.getHyperspaceAnchor(), 350);
	}
	
	public static void clearDeepHyper(SectorEntityToken entity, float radius) {
		// deep hyperspace removal (copypasted from UW)
		HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
		NebulaEditor editor = new NebulaEditor(plugin);

		float minRadius = plugin.getTileSize() * 2f;
		editor.clearArc(entity.getLocation().x, entity.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
		editor.clearArc(entity.getLocation().x, entity.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);
	}
	
	protected void addAntiochPart1(SectorAPI sector)
	{
		ProcgenUsedNames.notifyUsed("Antioch");
		ProcgenUsedNames.notifyUsed("Ascalon");
		new TEM_Antioch().generate(sector);
	}
	
	@Override
	public void generate(SectorAPI sector)
	{
		log.info("Starting sector generation...");
		
		ExerelinSetupData setupData = ExerelinSetupData.getInstance();
		boolean corvusMode = setupData.corvusMode;
		boolean grandSector = Global.getSettings().getModManager().isModEnabled("ZGrand Sector");
		boolean adjustedSector = Global.getSettings().getModManager().isModEnabled("Adjusted Sector");
		
		// use vanilla hyperspace map
		String hyperMap = "data/campaign/terrain/hyperspace_map.png";
		if (Global.getSettings().getModManager().isModEnabled("Vast Expanse")) {
			hyperMap = "data/campaign/terrain/Big_Hyperspace_Map.png";
		}
		else if (grandSector) {
			boolean generateHS = Global.getSettings().getBoolean("GrandSectorBoolHyperstorms");
			//log.info("Generating Grand Sector hyper map, hyperstorms: " + generateHS);
			if (generateHS)
				hyperMap = "data/campaign/terrain/anon_hyperspace.png";
			else
				hyperMap = "data/campaign/terrain/clear_skies.png";
		}
		else if (adjustedSector) {
			boolean generateHS = Global.getSettings().getBoolean("AdjustedSectorHS");
			if (generateHS)
				hyperMap = "data/campaign/terrain/hyperspace_new.png";
			else
				hyperMap = "data/campaign/terrain/no_storms.png";
		}		
		
		SectorEntityToken deep_hyperspace = Misc.addNebulaFromPNG(hyperMap,
			  0, 0, // center of nebula
			  sector.getHyperspace(), // location to add to
			  "terrain", "deep_hyperspace", // "nebula_blue", // texture to use, uses xxx_map for map
			  4, 4, Terrain.HYPERSPACE, StarAge.ANY); // number of cells in texture
		
		// make Prism before core systems, unless we're in random sector with one system
		// in which case we'll need to populate that system and then put Prism in it
		// FIXME: this is not actually implemented
		boolean prismBeforeSystems = corvusMode || setupData.numSystems > 1;
		SectorEntityToken prism = null;
		if (setupData.prismMarketPresent && prismBeforeSystems) {
			prism = addPrismMarket(sector, true);
		}
		
		if (corvusMode)
		{
			VanillaSystemsGenerator.generate(sector);
			if (grandSector || adjustedSector) {
				// ensure area around stars is clear
				// no need to do it in random sector, since ExerelinCoreSystemGenerator has its own clearer
				HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
				NebulaEditor editor = new NebulaEditor(plugin);
				float minRadius = plugin.getTileSize() * 2f;
				for (StarSystemAPI curr : sector.getStarSystems()) {
					float radius = curr.getMaxRadiusInHyperspace() * 0.5f;
					editor.clearArc(curr.getLocation().x, curr.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
					editor.clearArc(curr.getLocation().x, curr.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);
				}
			}
		}
		else
		{
			// make core constellation
			CustomConstellationParams params = new CustomConstellationParams(StarAge.ANY);
			int num = ExerelinSetupData.getInstance().numSystems;
			int min = num + 1;
			int max = (int)Math.max(num * 1.2f, num + 3);
			if (min < 14) min = 14;
			if (max < 18) max = 18;
			
			params.minStars = min;
			params.maxStars = max;
			params.location = SECTOR_CENTER;
			ExerelinCoreSystemGenerator gen = new ExerelinCoreSystemGenerator(params);
			gen.generate();
			
			//SectorEntityToken coreLabel = Global.getSector().getHyperspace().addCustomEntity("core_label_id", null, "core_label", null);
			//coreLabel.setFixedLocation(SECTOR_CENTER.getX(), SECTOR_CENTER.getY());
			
			if (ExerelinSetupData.getInstance().randomAntiochEnabled && (setupData.factions.containsKey("templars") 
					&& setupData.factions.get("templars")))
				addAntiochPart1(sector);
			
			if (setupData.prismMarketPresent && !prismBeforeSystems) {
				prism = addPrismMarket(sector, true);
			}
		}
		
		if (prism != null) {
			validateLocation(sector, prism.getStarSystem(), PRISM_LOC);
		}		
		
		log.info("Adding scripts and plugins");
		sector.registerPlugin(new CoreCampaignPluginImpl());
		sector.registerPlugin(new ExerelinCampaignPlugin());
		sector.addScript(new CoreScript());
		sector.addScript(new CoreEventProbabilityManager());
		sector.addScript(new EconomyFleetRouteManager());
		
		//sector.addScript(new PatrolFleetManagerReplacer());
		
		if (ExerelinModPlugin.HAVE_DYNASECTOR)
		{
			// FIXME
			/*
			sector.addScript(new DS_MercFleetManager());
			sector.addScript(new DS_LuddicPathFleetManager());
			sector.addScript(new DS_PirateFleetManager());
			//sector.addScript(new DS_BountyPirateFleetManager());
			*/
		}
		else
		{
			sector.addScript(new MercFleetManagerV2());
			sector.addScript(new DisposablePirateFleetManager());
			sector.addScript(new DisposableLuddicPathFleetManager());
			//sector.addScript(new BountyPirateFleetManager());
		}
		
		ExerelinModPlugin.addScripts();
		
		StatsTracker.create();
		
		DiplomacyManager.setRandomFactionRelationships(setupData.randomStartRelationships, 
				setupData.randomStartRelationshipsPirate);
		DiplomacyManager.initFactionRelationships(false);
		
		SectorManager.getManager().setCorvusMode(corvusMode);
		SectorManager.getManager().setHardMode(setupData.hardMode);
		SectorManager.getManager().setFreeStart(setupData.freeStart);
		
		String factionId = PlayerFactionStore.getPlayerFactionIdNGC();
		ExerelinFactionConfig conf = ExerelinConfig.getExerelinFactionConfig(factionId);
		if (conf.spawnAsFactionId != null && !conf.spawnAsFactionId.isEmpty())
		{
			factionId = conf.spawnAsFactionId;
		}
		
		// commission
		if (!factionId.equals(Factions.PLAYER)) {
			ExerelinUtilsFaction.grantCommission(factionId);
		}
				
		log.info("Finished sector generation");
	}
}