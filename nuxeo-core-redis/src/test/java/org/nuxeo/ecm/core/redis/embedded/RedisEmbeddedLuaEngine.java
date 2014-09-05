package org.nuxeo.ecm.core.redis.embedded;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptException;

import org.apache.commons.codec.binary.Hex;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.script.LuaScriptEngine;
import org.luaj.vm2.script.LuaScriptEngineFactory;
import org.luaj.vm2.script.LuajContext;
import org.nuxeo.ecm.core.api.NuxeoException;

import redis.clients.jedis.Jedis;

public class RedisEmbeddedLuaEngine {

    protected final Map<String, CompiledScript> binaries = new HashMap<>();

    protected final Jedis jedis;

    protected final LuaScriptEngine engine;

    public RedisEmbeddedLuaEngine(ReddisEmbeddedFactory factory) {
        jedis = factory.jedis;
        engine = initEngine(factory.jedis);
    }

    public String load(String content) throws ScriptException {
        String md5 = md5(content);
        CompiledScript chunk = null;
        try {
            chunk = engine.compile(content);
        } catch (ScriptException cause) {
            throw cause;
        }
        binaries.put(md5, chunk);
        return md5;
    }

    protected LuaScriptEngine initEngine(Jedis jedis) {
        LuaScriptEngine engine = (LuaScriptEngine) new LuaScriptEngineFactory().getScriptEngine();
        LuajContext context = (LuajContext) engine.getContext();
        LuaValue redis = context.globals.load(new RedisEmbeddedLuaLibrary(jedis));
        context.globals.set("redis", redis);
        return engine;
    }

    protected String md5(String content) {
        MessageDigest sha = null;
        try {
            sha = MessageDigest.getInstance("SHA");
        } catch (NoSuchAlgorithmException cause) {
            throw new NuxeoException("Cannot load sha digester", cause);
        }
        byte[] key = sha.digest(content.getBytes());
        return Hex.encodeHexString(key);
    }

    public boolean exists(String key) {
        return binaries.containsKey(key);
    }

    public void flush() {
        binaries.clear();
    }

    public Object evalsha(String sha, List<String> keys, List<String> args)
            throws ScriptException {
        final CompiledScript script = binaries.get(sha);
        Bindings bindings = engine.createBindings();
        bindings.put("KEYS", keys.toArray(new String[keys.size()]));
        bindings.put("ARGV", args.toArray(new String[args.size()]));
        Object result = script.eval(bindings);
        if (result instanceof LuaValue) {
            LuaValue value = (LuaValue) result;
            if (value.isboolean() && value.toboolean() == false) {
                return null;
            }
            return value.tojstring();
        }
        return result;
    }
}