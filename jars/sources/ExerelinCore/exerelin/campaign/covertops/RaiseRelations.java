package exerelin.campaign.covertops;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import exerelin.campaign.DiplomacyManager;
import exerelin.campaign.ExerelinReputationAdjustmentResult;
import exerelin.campaign.intel.agents.AgentIntel;
import exerelin.campaign.intel.agents.CovertActionIntel;
import java.util.Map;

public class RaiseRelations extends CovertOpsAction {

	public RaiseRelations(AgentIntel agentIntel, MarketAPI market, FactionAPI agentFaction, 
			FactionAPI targetFaction, boolean playerInvolved, Map<String, Object> params) {
		super(agentIntel, market, agentFaction, targetFaction, playerInvolved, params);
	}

	@Override
	public void onSuccess() {
		float effectMin = getDef().effect.one;
		float effectMax = getDef().effect.two;
		repResult = adjustRelations(
				agentFaction, targetFaction, effectMin, effectMax, null, null, null, true);

		reportEvent(repResult);
		
		DiplomacyManager.getManager().getDiplomacyBrain(targetFaction.getId()).reportDiplomacyEvent(
					agentFaction.getId(), repResult.delta);
	}

	@Override
	public void onFailure() {
		repResult = NO_EFFECT;
		if (result.isDetected())
		{
			repResult = adjustRelationsFromDetection(
					agentFaction, targetFaction, RepLevel.FAVORABLE, null, RepLevel.INHOSPITABLE, true);
		}
		reportEvent(repResult);
	}

	@Override
	public String getActionDefId() {
		return "raiseRelations";
	}

	@Override
	protected CovertActionIntel reportEvent(ExerelinReputationAdjustmentResult repResult) {
		return null;
	}
	
}
