package io.anuke.mindustry.core;

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Gdx;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.game.Difficulty;
import io.anuke.mindustry.game.GameMode;
import io.anuke.mindustry.io.SaveIO;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.Packets.ChatPacket;
import io.anuke.mindustry.world.Map;
import io.anuke.ucore.core.Effects;
import io.anuke.ucore.core.Sounds;
import io.anuke.ucore.modules.Module;
import io.anuke.ucore.util.CommandHandler;
import io.anuke.ucore.util.CommandHandler.Command;
import io.anuke.ucore.util.CommandHandler.Response;
import io.anuke.ucore.util.CommandHandler.ResponseType;
import io.anuke.ucore.util.Log;
import io.anuke.ucore.util.Strings;

import java.io.IOException;
import java.util.Scanner;

import static io.anuke.mindustry.Vars.*;
import static io.anuke.ucore.util.Log.*;

;

public class ServerControl extends Module {
    private final CommandHandler handler = new CommandHandler("");

    public ServerControl(){
        Effects.setScreenShakeProvider((a, b) -> {});
        Effects.setEffectProvider((a, b, c, d, e) -> {});
        Sounds.setHeadless(true);

        //override default handling of chat packets
        Net.handle(ChatPacket.class, (packet) -> {
            info("&y" + (packet.name == null ? "" : packet.name) +  ": &lb{0}", packet.text);
        });

        //don't do anything at all for GDX logging
        Gdx.app.setApplicationLogger(new ApplicationLogger() {
            @Override public void log(String tag, String message) { }
            @Override public void log(String tag, String message, Throwable exception) { }
            @Override public void error(String tag, String message) { }
            @Override public void error(String tag, String message, Throwable exception) { }
            @Override public void debug(String tag, String message) { }
            @Override public void debug(String tag, String message, Throwable exception) { }
        });

        registerCommands();
        Thread thread = new Thread(this::readCommands, "Server Controls");
        thread.setDaemon(true);
        thread.start();

        info("&lcServer loaded. Type &ly'help'&lc for help.");
    }

    private void registerCommands(){
        handler.register("help", "", "Displays this command list.", arg -> {
            info("Commands:");
            for(Command command : handler.getCommandList()){
                print("   &y" + command.text + (command.params.isEmpty() ? "" : " ") + command.params + " - &lm" + command.description);
            }
        });

        handler.register("exit", "", "Exit the server application.", arg -> {
            info("Shutting down server.");
            Net.dispose();
            Gdx.app.exit();
        });

        handler.register("stop", "", "Stop hosting the server.", arg -> {
            Net.closeServer();
            state.set(State.menu);
        });

        handler.register("host", "<mapname> <mode>", "Open the server with a specific map.", arg -> {
            if(state.is(State.playing)){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            String search = arg[0];
            Map result = null;
            for(Map map : world.maps().list()){
                if(map.name.equalsIgnoreCase(search))
                    result = map;
            }

            if(result == null){
                err("No map with name &y'{0}'&lg found.", search);
                return;
            }

            GameMode mode = null;
            try{
                mode = GameMode.valueOf(arg[1]);
            }catch (IllegalArgumentException e){
                err("No gamemode '{0}' found.", arg[1]);
                return;
            }

            info("Loading map...");
            state.mode = mode;

            logic.reset();
            world.loadMap(result);
            state.set(State.playing);
            info("Map loaded.");

            host();
        });

        handler.register("status", "", "Display server status.", arg -> {
            if(state.is(State.menu)){
                info("&lyStatus: &rserver closed");
            }else{
                info("&lyStatus: &lcPlaying on map &fi{0}&fb &lb/&lc Wave {1} &lb/&lcDifficulty {2}", Strings.capitalize(world.getMap().name), state.wave, state.difficulty.name());
                if(playerGroup.size() > 0) {
                    info("&lyPlayers: {0}", playerGroup.size());
                    for (Player p : playerGroup.all()) {
                        print("   &y" + p.name);
                    }
                }else{
                    info("&lyNo players connected.");
                }
            }
        });

        handler.register("say", "<message>", "Send a message to all players.", arg -> {
            if(!state.is(State.playing)) {
                err("Not hosting. Host a game first.");
                return;
            }

            netCommon.sendMessage("[pink][[Server]:[] " + arg[0]);
            info("&ly[Server]: &lb{0}", arg[0]);
        }).mergeArgs();

        handler.register("difficulty", "<difficulty>", "Set game difficulty.", arg -> {
            try{
                Difficulty diff = Difficulty.valueOf(arg[0]);
                state.difficulty = diff;
                info("Difficulty set to '{0}'.", arg[0]);
            }catch (IllegalArgumentException e){
                err("No difficulty with name '{0}' found.", arg[0]);
            }
        });

        handler.register("load", "<slot>", "Load a save from a slot.", arg -> {
            if(state.is(State.playing)){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }else if(!Strings.canParseInt(arg[0])){
                err("Invalid save slot '{0}'.", arg[0]);
                return;
            }

            int slot = Strings.parseInt(arg[0]);

            if(!SaveIO.isSaveValid(slot)){
                err("No save data found for slot.");
                return;
            }

            SaveIO.loadFromSlot(slot);
            info("Save loaded.");
            host();
            state.set(State.playing);
        });

        handler.register("save", "<slot>", "Save game state to a slot.", arg -> {
            if(!state.is(State.playing)){
                err("Not hosting. Host a game first.");
                return;
            }else if(!Strings.canParseInt(arg[0])){
                err("Invalid save slot '{0}'.", arg[0]);
                return;
            }

            int slot = Strings.parseInt(arg[0]);

            SaveIO.saveToSlot(slot);

            info("Saved to slot {0}.", slot);
        });
    }

    private void readCommands(){
        Scanner scan = new Scanner(System.in);
        while(true){
            String line = scan.nextLine();

            Gdx.app.postRunnable(() -> {
                Response response = handler.handleMessage(line);

                if (response.type == ResponseType.unknownCommand) {
                    err("Invalid command. Type 'help' for help.");
                } else if (response.type == ResponseType.invalidArguments) {
                    err("Invalid command arguments. Usage: " + response.command.text + " " + response.command.params);
                }
            });
        }
    }

    private void host(){
        try {
            Net.host(port);
        }catch (IOException e){
            Log.err(e);
            state.set(State.menu);
        }
    }
}
