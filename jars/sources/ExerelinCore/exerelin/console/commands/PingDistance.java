package exerelin.console.commands;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import java.util.List;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.CommonStrings;
import org.lazywizard.console.Console;
import org.lwjgl.util.vector.Vector2f;

public class PingDistance implements BaseCommand {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) {
            Console.showMessage(CommonStrings.ERROR_CAMPAIGN_ONLY);
            return CommandResult.WRONG_CONTEXT;
        }

        // do me!
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        List<MarketAPI> markets = Misc.getMarketsInLocation(playerFleet.getContainingLocation());
        
        Vector2f playerPos = playerFleet.getLocation();
        MarketAPI closestTargetMarket = null;
        float closestTargetDist = 9999999;
        
        for (MarketAPI market : markets) {
            float distance = Misc.getDistance(playerPos, market.getPrimaryEntity().getLocation());
            if (distance < closestTargetDist)
            {
                closestTargetDist = distance;
                closestTargetMarket = market;
            }
        }
        
        if (closestTargetMarket == null)
        {
            Console.showMessage("Unable to find target");
                return CommandResult.ERROR;
        }
        
        Console.showMessage("Distance to " + closestTargetMarket.getName() + ": " + closestTargetDist);
        
        return CommandResult.SUCCESS;
    }
}
