package org.dynmap.forge_1_20;

import java.io.File;

import org.apache.commons.lang3.tuple.Pair;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.Log;
import org.dynmap.forge_1_20.DynmapPlugin.OurLog;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.StartupMessageManager;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;;

@Mod("dynmap")
public class DynmapMod
{
    // The instance of your mod that Forge uses.
    public static DynmapMod instance;

    // Says where the client and server 'proxy' code is loaded.
    public static Proxy proxy = DistExecutor.runForDist(() -> ClientProxy::new, () -> Proxy::new);
    
    public static DynmapPlugin plugin;
    public static File jarfile;
    public static String ver;
    public static boolean useforcedchunks;

    public class APICallback extends DynmapCommonAPIListener {
        @Override
        public void apiListenerAdded() {
            if(plugin == null) {
                plugin = proxy.startServer(server);
            }
        }
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
        }
    } 
    
    //TODO
    //public class LoadingCallback implements net.minecraftforge.common.ForgeChunkManager.LoadingCallback {
    //    @Override
    //    public void ticketsLoaded(List<Ticket> tickets, World world) {
    //        if(tickets.size() > 0) {
    //            DynmapPlugin.setBusy(world, tickets.get(0));
    //            for(int i = 1; i < tickets.size(); i++) {
    //                ForgeChunkManager.releaseTicket(tickets.get(i));
    //            }
    //        }
    //    }
    //}

    public DynmapMod() {
    	instance = this;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::init);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, 
        		()->new IExtensionPoint.DisplayTest(()->NetworkConstants.IGNORESERVERONLY, (remote, isServer)-> true));

        Log.setLogger(new OurLog());      
        org.dynmap.modsupport.ModSupportImpl.init();
    }
    
    public void setup(final FMLCommonSetupEvent event)
    {
    	//TOOO
        jarfile = ModList.get().getModFileById("dynmap").getFile().getFilePath().toFile();

        ver = ModList.get().getModContainerById("dynmap").get().getModInfo().getVersion().toString();

        //// Load configuration file - use suggested (config/WesterosBlocks.cfg)
        //Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        //try {
        //    cfg.load();
        //    
        //    useforcedchunks = cfg.get("Settings",  "UseForcedChunks", true).getBoolean(true);
        //}
        //finally
        //{
        //    cfg.save();
        //}
    }

    public void init(FMLLoadCompleteEvent event)
    {
        /* Set up for chunk loading notice from chunk manager */
    	//TODO
        //if(useforcedchunks) {
        //    ForgeChunkManager.setForcedChunkLoadingCallback(DynmapMod.instance, new LoadingCallback());
        //}
        //else {
        //    Log.info("[Dynmap] World loading using forced chunks is disabled");
        //}
    }

    private MinecraftServer server;

    /* Set once the command tree has been registered at RegisterCommandsEvent. */
    public static boolean commandsRegistered = false;

    /* Forge 1.19+ fires RegisterCommandsEvent from the Commands constructor: this is the hook
       the game actually resolves commands against. Dynmap instead registered its commands at
       ServerAboutToStartEvent, which is too late - the nodes were added to the dispatcher but
       never became part of the command tree, so /dynmap, /dmap, /dmarker and /dynmapexp could
       not be used even though "Register commands" was logged. Register them here instead.

       The handlers resolve DynmapPlugin lazily, so this is safe to run before the plugin
       exists (it is created at ServerAboutToStartEvent). */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> cd = event.getDispatcher();
        new DynmapCommand(null).register(cd);
        new DmapCommand(null).register(cd);
        new DmarkerCommand(null).register(cd);
        new DynmapExpCommand(null).register(cd);
        commandsRegistered = true;
        Log.info("Register commands (RegisterCommandsEvent): dynmap=" + (cd.getRoot().getChild("dynmap") != null)
                + " dmap=" + (cd.getRoot().getChild("dmap") != null)
                + " dmarker=" + (cd.getRoot().getChild("dmarker") != null)
                + " dynmapexp=" + (cd.getRoot().getChild("dynmapexp") != null)
                + " rootchildren=" + cd.getRoot().getChildren().size());
    }

    @SubscribeEvent
    public void onServerStarting(ServerAboutToStartEvent event) {
        server = event.getServer();
        if(plugin == null)
            plugin = proxy.startServer(server);
		plugin.onStarting(server.getCommands().getDispatcher());

        /* Diagnostic: prove whether the nodes registered at RegisterCommandsEvent survived
           into the live dispatcher. "contains dynmap=false" here would mean the game executes
           against a different dispatcher instance - that would be the actual root cause. */
        CommandDispatcher<CommandSourceStack> live = server.getCommands().getDispatcher();
        Log.info("[Dynmap] live dispatcher: dynmap=" + (live.getRoot().getChild("dynmap") != null)
                + " dmap=" + (live.getRoot().getChild("dmap") != null)
                + " dmarker=" + (live.getRoot().getChild("dmarker") != null)
                + " dynmapexp=" + (live.getRoot().getChild("dynmapexp") != null)
                + " rootchildren=" + live.getRoot().getChildren().size());
	}
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        DynmapCommonAPIListener.register(new APICallback()); 
        plugin.serverStarted();
        plugin.apiAutoRender();   /* Dynmap-KubeJS API patch: config-driven startup render */
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent event)
    {
    	proxy.stopServer(plugin);
    	plugin = null;
    }
}
