package pw.ngea.bungeecensor;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import gnu.trove.map.TObjectIntMap;
import io.github.waterfallmc.travertine.protocol.MultiVersionPacketV17;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.Chat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class BungeeCensor extends Plugin implements Listener {

    public Configuration config = null;

    public Set<UUID> censorPlayers = Sets.newConcurrentHashSet();
    public Set<UUID> dirty = Sets.newConcurrentHashSet();

    public Connection conn;

    public Pattern censorRegex = null;

    public LoadingCache<String, String> cache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build(
                    new CacheLoader<String, String>() {
                        public String load(String message) {
                            return censorRegex.matcher(message).replaceAll("*");
                        }
                    });

    private Map<Integer, Integer> packetIds = new ConcurrentHashMap<>();

    private Field CHANNELWRAPPER;
    private Class<?> DIRECTIONDATA;
    private Method DIRECTIONDATA_GETPROTOCOLDATA;
    private Method DIRECTIONDATA_CREATEPACKET;
    private Class<?> PROTOCOLDATA;
    private Field PROTOCOLDATA_PACKETMAP;

    private Object GAME_TO_CLIENT; //Protocol.DirectionData Protocol.Game.GAME_TO_CLIENT

    @Override
    public void onEnable() {
        loadConfig();

        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new CensorCommand(this));
        try {
            Class.forName("com.mysql.jdbc.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:mysql://" +
                            config.getString("sql.host") + ":" +
                            config.getInt("sql.port") + "/" +
                            config.getString("sql.database"),
                    config.getString("sql.username"), config.getString("sql.password"));

            conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `censor` ("
                            + "  `uuid` VARCHAR(36) NOT NULL PRIMARY KEY"
                            + ");").executeUpdate();

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while loading database, disabling plugin", e);

            disable();
        }

        try {
            CHANNELWRAPPER = setAccessible(UserConnection.class.getDeclaredField("ch"));

            for (Class c : Protocol.class.getDeclaredClasses()) {
                if (c.getName().endsWith("DirectionData")) {
                    DIRECTIONDATA = c;
                }
            }

            DIRECTIONDATA_GETPROTOCOLDATA = DIRECTIONDATA.getDeclaredMethod("getProtocolData", int.class);
            DIRECTIONDATA_GETPROTOCOLDATA.setAccessible(true);
            DIRECTIONDATA_CREATEPACKET = DIRECTIONDATA.getDeclaredMethod("createPacket", int.class, int.class, boolean.class);
            DIRECTIONDATA_CREATEPACKET.setAccessible(true);

            GAME_TO_CLIENT = setAccessible(Protocol.GAME.getDeclaringClass().getDeclaredField("TO_CLIENT")).get(Protocol.GAME);

            for (Class c : Protocol.class.getDeclaredClasses()) {
                if (c.getName().endsWith("ProtocolData")) {
                    PROTOCOLDATA = c;
                }
            }

            PROTOCOLDATA_PACKETMAP = setAccessible(PROTOCOLDATA.getDeclaredField("packetMap"));

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while loading plugin, disabling", e);

            disable();
        }
    }

    @EventHandler
    public void onJoin(final PostLoginEvent event) {
        this.addChannel(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerDisconnectEvent event) {
        this.removeChannel(event.getPlayer());

        ProxiedPlayer player = event.getPlayer();
        if (dirty.remove(player.getUniqueId())) {
            if (censorPlayers.contains(player.getUniqueId())) {
                getProxy().getScheduler().runAsync(this, () -> {
                    try {
                        PreparedStatement statement = conn.prepareStatement("INSERT INTO `censor` VALUES (?);");
                        statement.setString(1, player.getUniqueId().toString());
                        statement.execute();
                    } catch (SQLException e) {
                        getLogger().log(Level.SEVERE, "Error while saving player", e);
                    }
                });
            } else {
                getProxy().getScheduler().runAsync(this, () -> {
                    try {
                        PreparedStatement statement = conn.prepareStatement("DELETE FROM `censor` WHERE `uuid` = ?;");
                        statement.setString(1, player.getUniqueId().toString());
                        statement.execute();
                    } catch (SQLException e) {
                        getLogger().log(Level.SEVERE, "Error while saving player", e);
                    }
                });
            }
        }
        censorPlayers.remove(player.getUniqueId());
    }

    public static ChannelWrapper getChannel(final ProxiedPlayer player) throws Exception {
        final ChannelWrapper channel = (ChannelWrapper) setAccessible(UserConnection.class.getDeclaredField("ch")).get(player);
        return channel;
    }

    public void addChannel(final ProxiedPlayer player) {
        try {
            final ChannelWrapper channel = getChannel(player);
            channel.getHandle().pipeline().addBefore("inbound-boss", "censor-handler", new ChannelDuplexHandler() {
                @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                    Object result = msg;
                    if (ByteBuf.class.isAssignableFrom(msg.getClass())) {
                        final ByteBuf buf = (ByteBuf) msg;
                        buf.markReaderIndex();
                        final int packetId = DefinedPacket.readVarInt(buf);
                        if (packetId != 0) {
                            int version = player.getPendingConnection().getVersion();

                            int chatId;
                            if (packetIds.containsKey(version)) {
                                chatId = packetIds.get(version);
                            } else {
                                Object data = DIRECTIONDATA_GETPROTOCOLDATA.invoke(GAME_TO_CLIENT, version);
                                chatId = ((TObjectIntMap<Class<? extends DefinedPacket>>) PROTOCOLDATA_PACKETMAP.get(data)).get(Chat.class);
                                packetIds.put(version, chatId);
                            }

                            if (packetId == chatId) {
                                DefinedPacket packet = (DefinedPacket) DIRECTIONDATA_CREATEPACKET.invoke(GAME_TO_CLIENT, packetId, version, false);
                                if (packet instanceof Chat) {
                                    Chat chat;
                                    try {
                                        if (packet instanceof MultiVersionPacketV17) {
                                            MultiVersionPacketV17 mv = (MultiVersionPacketV17) packet;
                                            mv.read0(buf, ProtocolConstants.Direction.TO_CLIENT, version);
                                            chat = (Chat) mv;
                                        } else {
                                            packet.read(buf, ProtocolConstants.Direction.TO_CLIENT, version);
                                            chat = (Chat) packet;
                                        }
                                        if (censorPlayers.contains(player.getUniqueId())) {
                                            chat.setMessage(cache.get(chat.getMessage()));
                                        }
                                    } finally {
                                        buf.release();
                                    }
                                    result = chat;
                                }
                            }
                        }
                        ((ByteBuf) msg).resetReaderIndex();
                    }
                    super.write(ctx, result, promise);
                }
            });
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while writing packet", e);
        }
    }

    public void removeChannel(final ProxiedPlayer player) {
        try {
            final ChannelWrapper channel = getChannel(player);
            channel.getHandle().pipeline().remove("censor-handler");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while removing channel", e);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error while disabling", e);
        }
    }

    @EventHandler
    public void onPreLogin(PostLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        getProxy().getScheduler().runAsync(this, () -> {
            try {
                PreparedStatement statement = conn.prepareStatement("SELECT * FROM `censor` WHERE `uuid` = ?;");
                statement.setString(1, uuid.toString());
                ResultSet rs = statement.executeQuery();
                if (rs.first()) {
                    censorPlayers.add(uuid);
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error during prelogin", e);
            }
        });
    }

    public boolean loadConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                try (InputStream is = getResourceAsStream("config.yml");
                        OutputStream os = new FileOutputStream(configFile)) {
                    ByteStreams.copy(is, os);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to create configuration file", e);
            }
        }
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            if (config == null) {
                getLogger().log(Level.SEVERE, "Error while loading config, disabling plugin", e);

                disable();
            } else {
                getLogger().log(Level.SEVERE, "Error while loading config", e);
            }
            return false;
        }
        cache.invalidateAll();
        censorRegex = Pattern.compile(censorWords(config.getStringList("words")));
        return true;
    }

    public void disable() {
        onDisable();
        for (Handler handler : this.getLogger().getHandlers()) {
            handler.close();
        }
        ProxyServer.getInstance().getScheduler().cancel(this);
        this.getExecutorService().shutdownNow();
    }

    public static String censorWords(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String w : list) {
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append(
                    String.format("(?i)(?<=(?=\\b%s\\b).{0,%d}).",
                            Pattern.quote(w),
                            w.length() - 1
                    )
            );
        }
        return sb.toString();
    }

    public static Field setAccessible(final Field f)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        f.setAccessible(true);
        final Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(f, f.getModifiers() & 0xFFFFFFEF);
        return f;
    }

}
