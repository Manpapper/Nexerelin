package exerelin.campaign.covertops;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import exerelin.campaign.ExerelinReputationAdjustmentResult;
import exerelin.campaign.intel.agents.AgentIntel;
import exerelin.campaign.intel.agents.CovertActionIntel;
import exerelin.utilities.StringHelper;
import java.util.Map;

public class DestabilizeMarket extends CovertOpsAction {

	public DestabilizeMarket(AgentIntel agentIntel, MarketAPI market, FactionAPI agentFaction, 
			FactionAPI targetFaction, boolean playerInvolved, Map<String, Object> params) {
		super(agentIntel, market, agentFaction, targetFaction, playerInvolved, params);
	}

	@Override
	public void onSuccess() {
		SectorAPI sector = Global.getSector();
		RecentUnrest.get(market).add(4, agentFaction.getDisplayName() + " " 
				+ StringHelper.getString("exerelin_marketConditions", "agentDestabilization"));
		
		repResult = adjustRepIfDetected(RepLevel.INHOSPITABLE, null);
	}

	@Override
	public void onFailure() {
		repResult = adjustRepIfDetected(RepLevel.INHOSPITABLE, RepLevel.HOSTILE);
		reportEvent(repResult);
	}	

	@Override
	public String getActionDefId() {
		return "destabilizeMarket";
	}

	@Override
	protected CovertActionIntel reportEvent(ExerelinReputationAdjustmentResult repResult) {
		return null;
	}
}
