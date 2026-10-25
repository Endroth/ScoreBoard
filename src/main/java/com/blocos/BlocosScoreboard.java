package com.blocos;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlocosScoreboard extends JavaPlugin implements Listener, CommandExecutor {

    private enum Mode { BROKEN, PLACED, AUTO }

    private static final String USO =
            "Uso: /s <quebrados|colocados|auto|on|off|placar [pagina]|reset>";

    // Segundos entre alternar "quebrados" e "colocados" no modo AUTO
    private static final int ALTERNAR_SEGUNDOS = 10;
    // O placar lateral do Minecraft mostra no maximo 15 linhas
    private static final int LINHAS_POR_PAGINA = 15;

    private final Map<UUID, Long> broken = new HashMap<>();
    private final Map<UUID, Long> placed = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Mode> modes = new HashMap<>();
    // jogadores que estao vendo o ranking (valor = pagina)
    private final Map<UUID, Integer> ranking = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        load();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("s").setExecutor(this);

        // Atualiza o scoreboard a cada 1 segundo
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 20L, 20L);

        // Salva a cada 5 minutos
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20L * 300, 20L * 300);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- Contagem ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        broken.merge(p.getUniqueId(), 1L, Long::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        placed.merge(p.getUniqueId(), 1L, Long::sum);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        update(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        boards.remove(id);
        ranking.remove(id);
    }

    // ---------- Scoreboard ----------

    private String nameOf(UUID id) {
        String n = names.get(id);
        if (n != null) return n;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        if (op.getName() != null) return op.getName();
        return id.toString().substring(0, 8);
    }

    private void update(Player p) {
        UUID id = p.getUniqueId();
        if (hidden.contains(id)) return;

        Scoreboard board = boards.computeIfAbsent(id,
                k -> Bukkit.getScoreboardManager().getNewScoreboard());

        Mode mode = modes.getOrDefault(id, Mode.AUTO);
        boolean showBroken;
        if (mode == Mode.AUTO) {
            showBroken = (System.currentTimeMillis() / 1000 / ALTERNAR_SEGUNDOS) % 2 == 0;
        } else {
            showBroken = mode == Mode.BROKEN;
        }
        Map<UUID, Long> data = showBroken ? broken : placed;

        Integer page = ranking.get(id);

        // lista ordenada do ranking (maior para o menor)
        List<Map.Entry<UUID, Long>> sorted = null;
        int pages = 1;
        int pg = 1;
        if (page != null) {
            sorted = new ArrayList<>(data.entrySet());
            sorted.removeIf(en -> en.getValue() <= 0);
            sorted.sort((a, b) -> {
                int c = Long.compare(b.getValue(), a.getValue());
                return c != 0 ? c : nameOf(a.getKey()).compareToIgnoreCase(nameOf(b.getKey()));
            });
            pages = Math.max(1, (sorted.size() + LINHAS_POR_PAGINA - 1) / LINHAS_POR_PAGINA);
            pg = Math.min(page, pages);
            ranking.put(id, pg);
        }

        String titleText;
        if (page == null) {
            titleText = showBroken ? "Blocos Quebrados" : "Blocos Colocados";
        } else {
            titleText = showBroken ? "Ranking Quebrados" : "Ranking Colocados";
            if (pages > 1) titleText += " " + pg + "/" + pages;
        }
        Component title = Component.text(titleText, NamedTextColor.YELLOW);

        Objective obj = board.getObjective("blocos");
        if (obj == null) {
            obj = board.registerNewObjective("blocos", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        obj.displayName(title);

        // limpa linhas antigas
        for (String entry : new HashSet<>(board.getEntries())) {
            board.resetScores(entry);
        }

        if (page == null) {
            long total = 0;
            for (long v : data.values()) total += v;
            obj.getScore("Total").setScore(clamp(total));
            obj.getScore(p.getName()).setScore(clamp(data.getOrDefault(id, 0L)));
        } else if (sorted.isEmpty()) {
            obj.getScore("Ninguem ainda").setScore(0);
        } else {
            int from = (pg - 1) * LINHAS_POR_PAGINA;
            int to = Math.min(from + LINHAS_POR_PAGINA, sorted.size());
            for (int i = from; i < to; i++) {
                Map.Entry<UUID, Long> en = sorted.get(i);
                obj.getScore(nameOf(en.getKey())).setScore(clamp(en.getValue()));
            }
        }

        if (p.getScoreboard() != board) p.setScoreboard(board);
    }

    private int clamp(long v) {
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    // ---------- Comando ----------

    private void send(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        if (args.length == 1 && (sub.equals("reset") || sub.equals("resetar"))) {
            if (!sender.hasPermission("blocos.reset")) {
                send(sender, "Você não tem permissão para isso.", NamedTextColor.RED);
                return true;
            }
            broken.clear();
            placed.clear();
            save();
            send(sender, "Contagem de blocos zerada.", NamedTextColor.GREEN);
            for (Player online : Bukkit.getOnlinePlayers()) update(online);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores podem usar este comando.");
            return true;
        }

        UUID id = p.getUniqueId();
        switch (sub) {
            case "quebrados" -> { hidden.remove(id); modes.put(id, Mode.BROKEN); update(p); }
            case "colocados" -> { hidden.remove(id); modes.put(id, Mode.PLACED); update(p); }
            case "auto", "on", "start" -> { hidden.remove(id); modes.put(id, Mode.AUTO); update(p); }
            case "off", "stop" -> {
                hidden.add(id);
                ranking.remove(id);
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            case "placar", "ranking" -> placar(p, args);
            default -> send(p, USO, NamedTextColor.YELLOW);
        }
        return true;
    }

    /** /s placar abre ou fecha o ranking; /s placar 2 abre a pagina 2. */
    private void placar(Player p, String[] args) {
        UUID id = p.getUniqueId();
        hidden.remove(id);

        if (args.length >= 2) {
            try {
                ranking.put(id, Math.max(1, Integer.parseInt(args[1])));
            } catch (NumberFormatException ex) {
                send(p, USO, NamedTextColor.YELLOW);
                return;
            }
        } else if (ranking.containsKey(id)) {
            ranking.remove(id);
            send(p, "Ranking fechado.", NamedTextColor.GREEN);
        } else {
            ranking.put(id, 1);
        }
        update(p);
    }

    // ---------- Persistência ----------

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        readSection(cfg, "broken", broken);
        readSection(cfg, "placed", placed);
        if (cfg.isConfigurationSection("names")) {
            for (String k : cfg.getConfigurationSection("names").getKeys(false)) {
                try {
                    names.put(UUID.fromString(k), cfg.getString("names." + k));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void readSection(YamlConfiguration cfg, String key, Map<UUID, Long> target) {
        if (!cfg.isConfigurationSection(key)) return;
        for (String k : cfg.getConfigurationSection(key).getKeys(false)) {
            try {
                target.put(UUID.fromString(k), cfg.getLong(key + "." + k));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        getDataFolder().mkdirs();
        YamlConfiguration cfg = new YamlConfiguration();
        broken.forEach((k, v) -> cfg.set("broken." + k, v));
        placed.forEach((k, v) -> cfg.set("placed." + k, v));
        names.forEach((k, v) -> cfg.set("names." + k, v));
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Erro ao salvar data.yml: " + ex.getMessage());
        }
    }
}
