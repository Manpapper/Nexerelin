package exerelin.campaign.intel;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import exerelin.plugins.ExerelinModPlugin;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.StringHelper;
import org.apache.log4j.Logger;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.FormatFlagsConversionMismatchException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InsuranceIntelV2 extends BaseIntelPlugin {
	public static Logger log = Global.getLogger(InsuranceIntelV2.class);
	
	public static final boolean DISPLAY_ONLY = true;
	public static final boolean INSURE_OFFICERS = false;
	
	public static final float COMPENSATION_PER_DMOD = 0.2f;
	public static final float LIFE_INSURANCE_PER_LEVEL = 2000f;
	public static final int POLICY_TERM = 365 * 2;
	public static final int PREMIUM_REDUCTION_INTERVAL = 365;
	public static final float PREMIUM_REDUCTION_MULT = 0.5f;
	public static final int RENEW_ADVANCE_TIME = 90;
	public static final float BASE_PREMIUM_RATIO = 0.1f;	// base premium is 10% of sum assured
	public static final int PREMIUM_MULT_DENOMINATOR = 100000;	// 100k
	public static final float BASE_PREMIUM_MULT = 2;
	public static final float MIN_PREMIUM_MULT = 0.5f;
	public static final float MAX_PREMIUM_MULT = 10;
	public static final int CLAIMS_HISTORY_DAYS = 90;
	
	public static final Tab BUTTON_FLEET = Tab.FLEET;
	public static final Tab BUTTON_CLAIMS = Tab.CLAIMS;
	public static final Tab BUTTON_HELP = Tab.HELP;
	public static final String BUTTON_INSURE_ALL = "btn_insureAll";
	
	protected transient Tab currentTab;
	
	protected IntervalUtil updateInterval = new IntervalUtil(0.25f, 0.25f);
	
	protected long lifetimePremiums;
	protected long lifetimeClaims;
	protected long premiumMultNumerator;
		
	protected List<InsuranceClaim> claimsHistory = new ArrayList<>();
	protected Map<String, InsurancePolicy> policies = new HashMap<>();
		
	public InsuranceIntelV2() {
		Global.getSector().getIntelManager().addIntel(this);
		Global.getSector().addScript(this);
	}
	
	/*
	protected int calcShipPayout(int baseValue, boolean destroyed, int dmods, int dmodsOld) 
	{
		float mult = ExerelinConfig.playerInsuranceMult;
		baseValue *= mult;
		if (destroyed) return Math.round(baseValue);
		
		float amount = 0;
		
		int delta = dmods - dmodsOld;
		float dmodMult = Math.min(delta * COMPENSATION_PER_DMOD, 1);
		amount += baseValue * dmodMult;
		
		if (dmodsOld == 0 && dmods > 0) {
			float depristineMult = Global.getSettings().getFloat("nex_insurance_newDHullMult");
			amount += baseValue * depristineMult;
		}
		
		return Math.round(amount);
	}
	*/
	
	protected int calcInsurableAmount(FleetMemberAPI member) {
		//return calcShipPayout(Math.round(member.getBaseValue()), true, 0, 0);
		return Math.round(member.getBaseValue() * ExerelinConfig.playerInsuranceMult);
	}
	
	protected float calcPremiumMult() {
		float mult = BASE_PREMIUM_MULT + (float)premiumMultNumerator/PREMIUM_MULT_DENOMINATOR;
		if (mult < MIN_PREMIUM_MULT) mult = MIN_PREMIUM_MULT;
		if (mult > MAX_PREMIUM_MULT) mult = MAX_PREMIUM_MULT;
		return mult;
	}
	
	protected int calcPremium(FleetMemberAPI member, float mult) {
		return Math.round(calcInsurableAmount(member) * BASE_PREMIUM_RATIO * mult);
	}
	
	public void insureShip(FleetMemberAPI member, float premiumMult) {
		int premium = calcPremium(member, premiumMult);
		int insuredAmount = calcInsurableAmount(member);
		
		InsurancePolicy policy = policies.get(member.getId());
		if (policy != null) {
			policy.insuredAmount = insuredAmount;
			policy.premium = premium;
			policy.ttl += POLICY_TERM;
			policy.updateDate();
		}
		else {
			policy = new InsurancePolicy(member, insuredAmount, premium);
			policies.put(member.getId(), policy);
		}
						
		if (!DISPLAY_ONLY)
			Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(premium);
		
		lifetimePremiums += premium;
	}
	
	public void insureAllShips() {
		float premiumMult = calcPremiumMult();
		for (FleetMemberAPI member : getAllShips()) {
			if (canInsure(member, premiumMult, true) == -1) continue;
			insureShip(member, premiumMult);
		}
	}
	
	/**
	 * Can we insure this ship?
	 * @param member
	 * @param premiumMult
	 * @param checkCredits If true, check whether the premium exceeds our current credits.
	 * @return -1 if cannot be insured, else the premium needed.
	 */
	public int canInsure(FleetMemberAPI member, float premiumMult, boolean checkCredits) 
	{
		InsurancePolicy policy = policies.get(member.getId());
		if (policy != null) {
			if (policy.ttl > RENEW_ADVANCE_TIME)
				return -1;
		}
		int premium = calcPremium(member, premiumMult);
		if (checkCredits && premium > getCredits())
		{
			return -1;
		}
		return premium;
	}
	
	public int canInsure(FleetMemberAPI member, boolean checkCredits) {
		return canInsure(member, calcPremiumMult(), checkCredits);
	}
	
	/**
	 * Check values for insuring all ships.
	 * @return Array of: Number of insurable ships, total insured amount, total premiums.
	 */
	public int[] checkInsureAll() {
		int count = 0, totalInsured = 0, totalPremium = 0;
		float premiumMult = calcPremiumMult();
		for (FleetMemberAPI member : getAllShips()) 
		{
			int premiumIfValid = canInsure(member, premiumMult, false);
			if (premiumIfValid != -1) {
				count++;
				totalPremium += premiumIfValid;
				totalInsured += calcInsurableAmount(member);
			}
		}
		
		return new int[] {count, totalInsured, totalPremium};
	}
	
	public List<FleetMemberAPI> getAllShips() {
		return Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
	}
	
	public float getCredits() {
		return Global.getSector().getPlayerFleet().getCargo().getCredits().get();
	}
	
	public void reportBattle(Map<FleetMemberAPI, Integer[]> disabledOrDestroyedMembers, 
			List<OfficerDataAPI> deadOfficers) {
		
		float paidAmount = 0;
		if (deadOfficers == null) deadOfficers = new ArrayList<>();
		paidAmount = processClaims(deadOfficers, disabledOrDestroyedMembers);
		
		premiumMultNumerator += paidAmount;
		lifetimeClaims += paidAmount;
		
		if (paidAmount > 0 && !DISPLAY_ONLY)
			Global.getSector().getPlayerFleet().getCargo().getCredits().add(paidAmount);
		
		if (paidAmount > 0) {
			sendUpdateIfPlayerHasIntel(paidAmount, false);
			currentTab = Tab.CLAIMS;
		}
			
	}	

	protected float processClaims(List<OfficerDataAPI> deadOfficers, Map<FleetMemberAPI, Integer[]> disabledOrDestroyedMembers) {
		float totalPayment = 0f;

		CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
		List<FleetMemberAPI> fleetCurrent = playerFleet.getFleetData().getMembersListCopy();
		//List<Pair<String, Float>> payoutList = new ArrayList<>();	// for update notification param
		
		for (FleetMemberAPI member : playerFleet.getFleetData().getSnapshot()) 
		{
			Integer[] entry = disabledOrDestroyedMembers.get(member);
			if (entry == null) continue;
			
			InsurancePolicy policy = policies.get(member.getId());
			if (policy == null) continue;
			
			float amount = 0;
			float baseAmount = policy.insuredAmount;
			
			// dead, not recovered
			if (!fleetCurrent.contains(member)) {
				amount = baseAmount;
				String text = getString("entryDescLost");
				
				InsuranceClaim claim = new InsuranceClaim(member, amount, text);
				claim.premium = policy.premium;
				claimsHistory.add(claim);
				policies.remove(member.getId());
			}
			// dead, recovered
			else {
				int dmodsOld = (int)entry[1];
				int dmods = countDMods(member);
				if (dmods <= dmodsOld) continue;
				
				boolean depristined = dmodsOld == 0 && dmods > 0;
				boolean dmodsIncreased = dmods > dmodsOld;
				
				String text = String.format(getString("entryDescRecovered"), dmodsOld, dmods);
				List<String> highlights = new ArrayList<>();
				highlights.add(dmodsOld + "");
				highlights.add(dmods + "");
				
				if (depristined) {
					float depristineMult = Global.getSettings().getFloat("nex_insurance_newDHullMult");
					amount += baseAmount * depristineMult;
					String multStr = toPercent(depristineMult);
										
					text += "\n" + String.format(getString("entryDescNewDHull"), multStr);
					highlights.add(multStr);
				}
				if (dmodsIncreased) {
					int delta = dmods - dmodsOld;
					float dmodMult = Math.min(delta * COMPENSATION_PER_DMOD, 1);
					amount += baseAmount * dmodMult;
					String multStr = toPercent(dmodMult);
					try {
						text += "\n" + String.format(getString("entryDescMoreDMods"), multStr);
						highlights.add(multStr);
					} catch (FormatFlagsConversionMismatchException ex) {
						//log.error("wtf " + ex.getFlags() + ", " + ex.getConversion());
					}
				}
				
				InsuranceClaim claim = new InsuranceClaim(member, amount, text);
				claim.premium = policy.premium;
				claim.highlights.addAll(highlights);
				claimsHistory.add(claim);
			}
			//payoutList.add(new Pair<>(member.getShipName(), amount));
			
			totalPayment += amount;
		}
		if (INSURE_OFFICERS && deadOfficers != null) {
			for (OfficerDataAPI deadOfficer : deadOfficers) {
				float amount = deadOfficer.getPerson().getStats().getLevel() * LIFE_INSURANCE_PER_LEVEL;
				log.info("Insuring dead officer " + deadOfficer.getPerson().getName().getFullName() + " for " + amount);
				totalPayment += amount;
				int level = deadOfficer.getPerson().getStats().getLevel();
				String text = String.format(getString("entryDescOfficerLevel"), level);
				
				InsuranceClaim item = new InsuranceClaim(deadOfficer, amount, text);
				item.highlights.add(level + "");
				claimsHistory.add(item);
			}
		}
		
		//sendUpdateIfPlayerHasIntel(payoutList, false);
		
		return totalPayment;
	}

	public static int countDMods(FleetMemberAPI member) {
		int dmods = 0;
		for (String mod : member.getVariant().getPermaMods()) {
			if (Global.getSettings().getHullModSpec(mod).hasTag("dmod") 
					&& !member.getVariant().getSuppressedMods().contains(mod)) {
				//log.info("  -" + mod);
				dmods++;
			}
		}
		log.info("Fleet member " + member.getShipName() + " has " + dmods + " D-mods");
		return dmods;
	}
		
	protected void advancePoliciesAndClaims(float days) {
		// advance time on policies
		List<String> policiesToRemove = new ArrayList<>();
		Set<String> currFleetMembers = new HashSet<>();
		for (FleetMemberAPI member : getAllShips()) {
			currFleetMembers.add(member.getId());
		}
		
		for (String uuId : policies.keySet()) {
			if (!currFleetMembers.contains(uuId))
				continue;
			
			InsurancePolicy policy = policies.get(uuId);
			policy.ttl -= days;
			policy.bonusCountdown -= days;
			
			if (policy.ttl <= 0) {
				policiesToRemove.add(uuId);
				premiumMultNumerator -= policy.premium * PREMIUM_REDUCTION_MULT;
			}
			else if (policy.bonusCountdown <= 0) {
				policy.bonusCountdown += PREMIUM_REDUCTION_INTERVAL;
				premiumMultNumerator -= policy.premium * PREMIUM_REDUCTION_MULT;
			}
		}
		if (!policiesToRemove.isEmpty()) {
			Collections.sort(policiesToRemove, new Comparator<String>() {
				@Override
				public int compare(String id1, String id2) {
					return -Integer.compare(policies.get(id1).insuredAmount, policies.get(id2).insuredAmount);
				}
			});
			
			sendUpdateIfPlayerHasIntel(policiesToRemove, false);
			
			for (String uuId : policiesToRemove) {
				policies.remove(uuId);
			}
		}
		
		// advance time on claims
		List<InsuranceClaim> claimsToRemove = new ArrayList<>();
		for (InsuranceClaim claim : claimsHistory) {
			claim.age += days;
			if (claim.age > CLAIMS_HISTORY_DAYS) {
				claimsToRemove.add(claim);
			}
		}
		claimsHistory.removeAll(claimsToRemove);
	}
	
	@Override
	protected void advanceImpl(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);
		updateInterval.advance(days);
		if (!updateInterval.intervalElapsed()) return;
		
		days = updateInterval.getElapsed();
		advancePoliciesAndClaims(days);		
	}
	
	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color c = Misc.getBasePlayerColor();
		info.addPara(getName(), c, 0f);
		bullet(info);

		float pad = 3f;
		Color tc = getBulletColorForMode(mode);
		Color h = Misc.getHighlightColor();
		
		// insurance payout notification
		if (listInfoParam instanceof Float)
			info.addPara(getString("bulletPayment"), pad, tc, h, Misc.getDGSCredits((float)listInfoParam));
		
		// insurance expiry notification
		else if (listInfoParam instanceof List) {
			List<String> memberIds = (List<String>)listInfoParam;
			
			Map<String, FleetMemberAPI> membersById = new HashMap<>();
			for (FleetMemberAPI member : getAllShips()) {
				membersById.put(member.getId(), member);
			}			
			
			int count = 0;
			for (int i=0; i<3; i++) {
				if (i >= memberIds.size() - 1) break;
				
				String id = memberIds.get(i);
				FleetMemberAPI member = membersById.get(id);
				if (member == null) continue;
				info.addPara(member.getShipName() + ", " + member.getHullSpec().getHullNameWithDashClass(), 
						i == 0 ? pad : 0, h, member.getShipName());
				count++;
			}
			int extra = memberIds.size() - count;
			if (extra > 0) {
				info.addPara(getString("bulletExpiredAndOthers"), 0, h, extra + "");
			}
		}
	}

	@Override
	public String getSmallDescriptionTitle() {
		return getName();
	}

	protected String getName() {
		if (listInfoParam != null && listInfoParam instanceof List)
			return getString("titleV2Expire");
		return getString("titleV2");
	}
	
	@Override
	public boolean hasSmallDescription() {
		return false;
	}

	@Override
	public boolean hasLargeDescription() { 
		return true;
	}
	
	public static int TAB_BUTTON_HEIGHT = 20;
	public static int TAB_BUTTON_WIDTH = 180;
	public static int ENTRY_HEIGHT = 80;
	public static int ENTRY_WIDTH = 300;
	public static int IMAGE_WIDTH = 80;
	public static int INSURE_BUTTON_WIDTH = 120;
	public static int IMAGE_DESC_GAP = 12;
	
	/**
	 * Generates an image of a ship or officer on the left-hand side of the screen. 
	 * @param panel
	 * @param member Takes priority over {@code officer}.
	 * @param officer
	 * @return 
	 */
	protected TooltipMakerAPI generateImage(CustomPanelAPI panel, FleetMemberAPI member, OfficerDataAPI officer) 
	{
		TooltipMakerAPI image = panel.createUIElement(IMAGE_WIDTH, ENTRY_HEIGHT, false);
		
		if (member != null) {
			List<FleetMemberAPI> ship = new ArrayList<>();
			ship.add(member);
			image.addShipList(1, 1, IMAGE_WIDTH, Color.WHITE, ship, 0);
		} else {
			image.addImage(officer.getPerson().getPortraitSprite(), IMAGE_WIDTH-8, 0);
		}
		return image;
	}
	
	protected String toPercent(float num) {
		return String.format("%.0f", num * 100) + "%";
	}
	
	protected void createFleetView(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
		float pad = 3;
		float opad = 10;
		Color h = Misc.getHighlightColor();
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return;
		
		float heightPerItem = ENTRY_HEIGHT + opad;
		int numItems = player.getFleetData().getMembersListCopy().size();
		float itemPanelHeight = heightPerItem * numItems;
		CustomPanelAPI itemPanel = panel.createCustomPanel(ENTRY_WIDTH, itemPanelHeight, null);
		float yPos = opad;
		
		float premiumMult = calcPremiumMult();
		
		info.addButton(getString("buttonInsureAll"), BUTTON_INSURE_ALL, 120, 16, opad);
		
		for (FleetMemberAPI member : player.getFleetData().getMembersListCopy()) 
		{
			TooltipMakerAPI image = generateImage(itemPanel, member, null);
			itemPanel.addUIElement(image).inTL(4, yPos);
			
			TooltipMakerAPI entry = itemPanel.createUIElement(width - IMAGE_WIDTH - IMAGE_DESC_GAP,
					ENTRY_HEIGHT, true);
			entry.addPara(member.getShipName(), h, 0);
			
			InsurancePolicy policy = policies.get(member.getId());
			// display policy information if one is in force
			if (policy != null) {
				String amount = Misc.getDGSCredits(policy.insuredAmount);
				String text = getFormattedDateString(getString("entryDescAmount"), policy.date);
				entry.addPara(text, pad, h, amount);
				
				String premium = Misc.getDGSCredits(policy.premium);
				entry.addPara(getString("entryDescPremium"), 0, h, premium);
				entry.addPara(getString("entryDescDaysRemaining"), 0, h, Math.round(policy.ttl) + "");
				
			} else {	// no policy, display prospective information
				String amount = Misc.getDGSCredits(calcInsurableAmount(member));
				entry.addPara(getString("entryDescPotentialAmount"), 0, h, amount);
				
				String premium = Misc.getDGSCredits(calcPremium(member, premiumMult));
				entry.addPara(getString("entryDescPremium"), 0, h, premium);
			}
			//itemPanel.addUIElement(entry).inTL(4 + IMAGE_WIDTH + IMAGE_DESC_GAP, yPos);
			itemPanel.addUIElement(entry).rightOfTop(image, IMAGE_DESC_GAP);
			
			/*
			TooltipMakerAPI buttonHolder = itemPanel.createUIElement(INSURE_BUTTON_WIDTH, 16, false);
			String name = policy == null ? "Insure" : "Renew";
			buttonHolder.addButton(name, member, 120, 16, 0);
			itemPanel.addUIElement(buttonHolder).inTL(4 + IMAGE_WIDTH + IMAGE_DESC_GAP + ENTRY_WIDTH, yPos);
			*/
			
			yPos += ENTRY_HEIGHT + opad;
		}
		info.addCustom(itemPanel, 0);
	}
	
	protected void createClaimsView(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
		float pad = 3;
		float opad = 10;
		Color h = Misc.getHighlightColor();
		
		info.addPara(getString("descHistoryTime"), opad, h, CLAIMS_HISTORY_DAYS + "");
		
		float heightPerItem = ENTRY_HEIGHT + opad;
		float itemPanelHeight = heightPerItem * claimsHistory.size();
		CustomPanelAPI itemPanel = panel.createCustomPanel(width, itemPanelHeight, null);
		float yPos = opad;
		
		List<InsuranceClaim> claims = new ArrayList<>(claimsHistory);
		Collections.reverse(claims);
		
		for (InsuranceClaim claim : claims) {
			//log.info("Ship " + item.member.getShipName() + " has " + countDMods(item.member) + " D-mods");
			TooltipMakerAPI image = generateImage(itemPanel, claim.member, claim.officer);
			
			String name;
			if (claim.member != null) {
				name = claim.member.getShipName();
			} else {
				name = claim.officer.getPerson().getNameString();
			}
			
			TooltipMakerAPI entry = itemPanel.createUIElement(width - IMAGE_WIDTH - IMAGE_DESC_GAP,
					ENTRY_HEIGHT, true);
			entry.addPara(name, h, 0);
			if (claim.payment > 0) {
				String payout = Misc.getDGSCredits(claim.payment);
				String text = getFormattedDateString(getString("entryDescAmountPaid"), claim.date);
				entry.addPara(text, 0, h, payout);
			}

			LabelAPI desc = entry.addPara(claim.desc, 0);
			desc.setHighlight(claim.highlights.toArray(new String[0]));
			desc.setHighlightColor(h);
			
			itemPanel.addUIElement(image).inTL(4, yPos);
			itemPanel.addUIElement(entry).inTL(4 + IMAGE_WIDTH + IMAGE_DESC_GAP, yPos);
			
			yPos += ENTRY_HEIGHT + opad;
			//break;
		}
		//info.addPara(getString("descBaseValue"), opad);
		info.addCustom(itemPanel, 0);
	}
	
	protected void createHelpView(TooltipMakerAPI info) {
		float opad = 10;
		float pad = 3;
		Color h = Misc.getHighlightColor();
		
		info.setParaInsigniaLarge();
		info.addPara(getString("helpPara1Title"), opad);
		info.setParaFontDefault();
		TooltipMakerAPI section = info.beginImageWithText("graphics/icons/intel/fleet_log3.png", 32);
		section.setBulletedListMode("    - ");
		section.addPara(getString("helpPara1-1"), pad, h, POLICY_TERM + "");
		section.addPara(getString("helpPara1-2"), pad, h, ExerelinConfig.playerInsuranceMult + "", 
				toPercent(BASE_PREMIUM_RATIO));
		section.addPara(getString("helpPara1-3"), pad, h, RENEW_ADVANCE_TIME + "", POLICY_TERM + "");
		info.addImageWithText(opad);
		
		info.setParaInsigniaLarge();
		info.addPara(getString("helpPara2Title"), opad);
		info.setParaFontDefault();
		section = info.beginImageWithText("graphics/icons/intel/bounties2.png", 32);
		section.setBulletedListMode("    - ");
		section.addPara(getString("helpPara2-1"), pad);
		section.addPara(getString("helpPara2-2"), pad, h, 
				toPercent(COMPENSATION_PER_DMOD), 
				toPercent(Global.getSettings().getFloat("nex_insurance_newDHullMult")));
		section.addPara(getString("helpPara2-3"), pad);
		info.addImageWithText(opad);
		unindent(info);
		
		info.setParaInsigniaLarge();
		info.addPara(getString("helpPara3Title"), opad);
		info.setParaFontDefault();
		section = info.beginImageWithText("graphics/icons/intel/gain_credits.png", 32);
		section.setBulletedListMode("    - ");
		section.addPara(getString("helpPara3-1"), pad);
		section.addPara(getString("helpPara3-2"), pad, h, MIN_PREMIUM_MULT + "", 
				MAX_PREMIUM_MULT + "", PREMIUM_REDUCTION_INTERVAL + "");
		section.addPara(getString("helpPara3-3"), pad, h, BASE_PREMIUM_MULT + "", 
				Misc.getWithDGS(PREMIUM_MULT_DENOMINATOR));
		info.addImageWithText(opad);
		unindent(info);
		
		section = info.beginImageWithText(Global.getSettings().getSpriteName("misc", "nex_ironbank_logo"), 128);
		String str = String.format(getString("helpFooter"), Global.getSector().getClock().getCycle());
		section.addPara(str, pad);
		info.addImageWithText(opad*2);
	}
	
	protected TooltipMakerAPI addTabButtons(TooltipMakerAPI tm, CustomPanelAPI panel, float width) {
		
		//CustomPanelAPI row = panel.createCustomPanel(width, TAB_BUTTON_HEIGHT, null);
		//CustomPanelAPI spacer = panel.createCustomPanel(width, TAB_BUTTON_HEIGHT, null);
		
		TooltipMakerAPI btnHolder1 = panel.createUIElement(TAB_BUTTON_WIDTH, 
				TAB_BUTTON_HEIGHT, false);
		btnHolder1.addButton(getString("tabFleet"), BUTTON_FLEET, TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, 0);
		panel.addUIElement(btnHolder1).inTL(0, 3);
		
		TooltipMakerAPI btnHolder2 = panel.createUIElement(TAB_BUTTON_WIDTH, 
				TAB_BUTTON_HEIGHT, false);
		btnHolder2.addButton(getString("tabClaims"), BUTTON_CLAIMS, TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, 0);
		panel.addUIElement(btnHolder2).rightOfTop(btnHolder1, 4);
		
		TooltipMakerAPI btnHolder3 = panel.createUIElement(TAB_BUTTON_WIDTH, 
				TAB_BUTTON_HEIGHT, false);
		btnHolder3.addButton(getString("tabHelp"), BUTTON_HELP, TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, 0);
		panel.addUIElement(btnHolder3).rightOfTop(btnHolder2, 4);
		
		//tm.addCustom(row, 0);
		//tm.addCustom(spacer, 0);
		//tm.setButtonFontDefault();
		
		return btnHolder1;
	}
	
	@Override
	public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
		float opad = 10;
		float pad = 3;
		Color h = Misc.getHighlightColor();
		
		if (currentTab == null) currentTab = Tab.FLEET;
				
		TooltipMakerAPI info = panel.createUIElement(width, height - TAB_BUTTON_HEIGHT - 4, true);
		FactionAPI faction = Global.getSector().getPlayerFaction();
		
		TooltipMakerAPI buttonHolder = addTabButtons(info, panel, width);
		
		info.addSectionHeading(getString("headerTitle"), faction.getBaseUIColor(), 
			faction.getDarkUIColor(), com.fs.starfarer.api.ui.Alignment.MID, opad);
		
		float insurMult = ExerelinConfig.playerInsuranceMult;	
		info.addPara(getString("descInsuranceMult"), opad, h, insurMult + "");
		
		String premiumMult = String.format("%.2f", calcPremiumMult());
		info.addPara(getString("descPremiumMult"), pad, h, premiumMult);
		
		String lifetime = getString("descLifetimePremiums") + ": %s";
		info.addPara(lifetime, pad, h, Misc.getDGSCredits(lifetimePremiums));
		lifetime = getString("descLifetimeClaims") + ": %s";
		info.addPara(lifetime, pad, h, Misc.getDGSCredits(lifetimeClaims));
		
		switch (currentTab) {
			case FLEET:
				createFleetView(panel, info, width);
				break;
			case CLAIMS:
				createClaimsView(panel, info, width);
				break;
			case HELP:
				createHelpView(info);
				break;
		}
		
		panel.addUIElement(info).belowLeft(buttonHolder, pad);
	}
	
	@Override
	public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
		if (buttonId == BUTTON_INSURE_ALL) {
			String key = "confirmPromptInsureAll";
			int[] insureAll = checkInsureAll();
			
			if (insureAll[0] == 0) {
				prompt.addPara(getString(key + "NoShips"), 0);
				return;
			}
			
			int totalPremiums = insureAll[2];
			float credits = getCredits();
			if (totalPremiums > credits) {
				key += "NotEnoughCredits";
			}
			
			prompt.addPara(getString(key), 0, Misc.getHighlightColor(), 
					insureAll[0] + "", 
					Misc.getDGSCredits(insureAll[1]), 
					Misc.getDGSCredits(insureAll[2]), 
					Misc.getDGSCredits(credits));
		}
	}
	
	@Override
	public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
		if (buttonId instanceof Tab) {
			currentTab = (Tab)buttonId;
			ui.updateUIForItem(this);
		}
		if (buttonId == BUTTON_INSURE_ALL) {
			insureAllShips();
			ui.updateUIForItem(this);
		}
	}
	
	@Override
	public boolean doesButtonHaveConfirmDialog(Object buttonId) {
		if (buttonId == BUTTON_INSURE_ALL) return true;
		
		return false;
	}
	
	@Override
	public String getConfirmText(Object buttonId) {
		return super.getConfirmText(buttonId);
	}
	
	@Override
	public String getCancelText(Object buttonId) {
		if (buttonId == BUTTON_INSURE_ALL) {
			int[] insureAll = checkInsureAll();
			if (insureAll[0] == 0) return null;	// no ships to insure
			int totalPremiums = insureAll[2];
			float credits = getCredits();
			if (totalPremiums > credits) {
				return null;
			}
		}
		
		return super.getCancelText(buttonId);
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(getString("intelTag"));
		return tags;
	}

	@Override
	public String getIcon() {
		return Global.getSettings().getSpriteName("intel", "credits");
	}
	
	@Override
	public boolean isHidden() {
		return !ExerelinModPlugin.isNexDev;
	}

	@Override
	protected void notifyEnded() {
		Global.getSector().getIntelManager().removeIntel(this);
		Global.getSector().removeScript(this);
	}
	
	protected static String getString(String id) {
		return StringHelper.getString("nex_insurance", id);
	}
	
	public String getFormattedDateString(String format, int[] date) {
		format = StringHelper.substituteToken(format, "$day", date[0] + "");
		format = StringHelper.substituteToken(format, "$month", date[1] + "");
		format = StringHelper.substituteToken(format, "$year", date[2] + "");
		return format;
	}
	
	public static class InsuranceClaim {
		public FleetMemberAPI member;
		public OfficerDataAPI officer;
		public float payment;
		public int premium;
		public String desc;
		public List<String> highlights = new ArrayList<>();
		public int[] date;
		public float age;
		
		private InsuranceClaim(float payment, String desc) {
			this.payment = payment;
			this.desc = desc;
			CampaignClockAPI clock = Global.getSector().getClock();
			date = new int[]{clock.getDay(), clock.getMonth(), clock.getCycle()};
		}
		
		public InsuranceClaim(FleetMemberAPI member, float payment, String desc) 
		{
			this(payment, desc);
			this.member = member;
		}
		
		public InsuranceClaim(OfficerDataAPI officer, float payment, String desc) 
		{
			this(payment, desc);
			this.officer = officer;
		}
	}
	
	public static class InsurancePolicy {
		public String uuId;
		public boolean isOfficer;
		public int premium;
		public int insuredAmount;
		public long timestamp;
		public int[] date;
		public float ttl = POLICY_TERM;
		public float bonusCountdown = PREMIUM_REDUCTION_INTERVAL;	// time before premium reduction is applied
		
		public InsurancePolicy(FleetMemberAPI member, int insuredAmount, int premium) {
			uuId = member.getId();
			this.premium = premium;
			this.insuredAmount = insuredAmount;
			updateDate();			
		}
		
		public void updateDate() {
			CampaignClockAPI clock = Global.getSector().getClock();
			timestamp = clock.getTimestamp();
			date = new int[]{clock.getDay(), clock.getMonth(), clock.getCycle()};
		}
	}
	
	public static enum Tab {
		FLEET, CLAIMS, HELP
	}
}
