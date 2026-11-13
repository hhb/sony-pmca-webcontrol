package com.sony.pmca.webcontrol;

import android.hardware.Camera;
import android.util.Pair;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reflection-based proxy for CameraEx.ParametersModifier.
 * Scans and caches all whitelisted methods once at startup so runtime overhead is microseconds.
 */
public class ModifierProxy {
    private final Map<String, List<Method>> m_methodsByName = new HashMap<String, List<Method>>();
    private final List<Method> m_allMethods = new ArrayList<Method>();

    public ModifierProxy() {
        Method[] declared = CameraEx.ParametersModifier.class.getDeclaredMethods();
        for (Method m : declared) {
            String name = m.getName();
            if (isAllowedMethod(name)) {
                m_allMethods.add(m);
                List<Method> list = m_methodsByName.get(name);
                if (list == null) {
                    list = new ArrayList<Method>();
                    m_methodsByName.put(name, list);
                }
                list.add(m);
            }
        }
        Collections.sort(m_allMethods, new Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                return a.getName().compareTo(b.getName());
            }
        });
    }

    private static boolean isAllowedMethod(String name) {
        if ("tryIgnoreInhibit".equals(name)) {
            return false;
        }
        return name.startsWith("is") || name.startsWith("get") || name.startsWith("set");
    }

    public boolean hasMethod(String name) {
        return m_methodsByName.containsKey(name);
    }

    public JSONObject listMethodsJson() {
        JSONArray arr = new JSONArray();
        for (Method m : m_allMethods) {
            try {
                JSONObject item = new JSONObject();
                item.put("name", m.getName());
                item.put("returnType", m.getReturnType().getSimpleName());
                JSONArray pArr = new JSONArray();
                for (Class<?> pt : m.getParameterTypes()) {
                    pArr.put(pt.getSimpleName());
                }
                item.put("params", pArr);
                arr.put(item);
            } catch (Throwable ignored) {
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("ok", true);
            root.put("count", arr.length());
            root.put("methods", arr);
        } catch (Throwable ignored) {
        }
        return root;
    }

    public JSONObject invokeGet(CameraEx camera, String methodName, List<String> rawArgs) throws Exception {
        Method m = findBestMethod(methodName, rawArgs != null ? rawArgs.size() : 0);
        Camera.Parameters params = camera.getNormalCamera().getParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(params);
        Object[] args = convertArgs(m.getParameterTypes(), rawArgs);
        Object res = m.invoke(mod, args);

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("method", m.getName());
        root.put("result", toJsonValue(res));
        return root;
    }

    public JSONObject invokeBatchGet(CameraEx camera, List<String> names) throws Exception {
        Camera.Parameters params = camera.getNormalCamera().getParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(params);

        JSONObject results = new JSONObject();
        JSONObject errors = new JSONObject();
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            String targetName = resolveGetterName(trimmed);
            try {
                Method m = findBestMethod(targetName, 0);
                Object res = m.invoke(mod);
                results.put(trimmed, toJsonValue(res));
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                errors.put(trimmed, cause.toString());
            }
        }

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("results", results);
        if (errors.length() > 0) {
            root.put("errors", errors);
        }
        return root;
    }

    public JSONObject invokeSet(CameraEx camera, String methodName, List<String> rawArgs) throws Exception {
        Method m = findBestMethod(methodName, rawArgs != null ? rawArgs.size() : 0);
        Camera.Parameters params = camera.createEmptyParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(params);
        Object[] args = convertArgs(m.getParameterTypes(), rawArgs);
        m.invoke(mod, args);
        camera.getNormalCamera().setParameters(params);

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("method", m.getName());
        return root;
    }

    public JSONObject invokeBatchSet(CameraEx camera, JSONObject body) throws Exception {
        Camera.Parameters params = camera.createEmptyParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(params);

        JSONArray applied = new JSONArray();
        Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String methodName = resolveSetterName(key);
            Object rawVal = body.get(key);
            List<String> strArgs = new ArrayList<String>();
            if (rawVal instanceof JSONArray) {
                JSONArray ja = (JSONArray) rawVal;
                for (int i = 0; i < ja.length(); i++) {
                    strArgs.add(String.valueOf(ja.get(i)));
                }
            } else {
                strArgs.add(String.valueOf(rawVal));
            }
            Method m = findBestMethod(methodName, strArgs.size());
            Object[] args = convertArgs(m.getParameterTypes(), strArgs);
            m.invoke(mod, args);
            applied.put(methodName);
        }
        camera.getNormalCamera().setParameters(params);

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("applied", applied);
        return root;
    }

    public JSONObject getCapabilities(CameraEx camera, JSONObject lensJson) {
        JSONObject caps = new JSONObject();
        try {
            Camera.Parameters params = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(params);
            for (Method m : m_allMethods) {
                String name = m.getName();
                if (name.startsWith("is") && m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                    try {
                        Object val = m.invoke(mod);
                        caps.put(name, val);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        JSONObject root = new JSONObject();
        try {
            root.put("ok", true);
            root.put("lens", lensJson != null ? lensJson : new JSONObject());
            root.put("flags", caps);
        } catch (Throwable ignored) {
        }
        return root;
    }

    private String resolveGetterName(String name) {
        if (m_methodsByName.containsKey(name)) {
            return name;
        }
        String getCand = "get" + name;
        if (m_methodsByName.containsKey(getCand)) {
            return getCand;
        }
        String isCand = "is" + name;
        if (m_methodsByName.containsKey(isCand)) {
            return isCand;
        }
        return name;
    }

    private String resolveSetterName(String name) {
        if (m_methodsByName.containsKey(name)) {
            return name;
        }
        String setCand = "set" + name;
        if (m_methodsByName.containsKey(setCand)) {
            return setCand;
        }
        return name;
    }

    private Method findBestMethod(String name, int argCount) throws NoSuchMethodException {
        List<Method> list = m_methodsByName.get(name);
        if (list == null || list.isEmpty()) {
            throw new NoSuchMethodException("Unknown modifier method: " + name);
        }
        for (Method m : list) {
            if (m.getParameterTypes().length == argCount) {
                return m;
            }
        }
        return list.get(0);
    }

    private static Object[] convertArgs(Class<?>[] types, List<String> rawArgs) {
        if (types == null || types.length == 0) {
            return new Object[0];
        }
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String raw = (rawArgs != null && i < rawArgs.size()) ? rawArgs.get(i) : null;
            out[i] = convertArg(types[i], raw);
        }
        return out;
    }

    private static Object convertArg(Class<?> type, String raw) {
        if (raw == null) {
            if (type == int.class) return 0;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(raw.trim());
        }
        if (type == boolean.class || type == Boolean.class) {
            String s = raw.trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
        if (type == String.class) {
            return raw;
        }
        if (type == Pair.class) {
            String[] parts = raw.split(",");
            int a = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
            int b = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return new Pair<Integer, Integer>(a, b);
        }
        if (type == int[].class) {
            if (raw.trim().isEmpty()) return new int[0];
            String[] parts = raw.split(",");
            int[] arr = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                arr[i] = Integer.parseInt(parts[i].trim());
            }
            return arr;
        }
        return raw;
    }

    public static Object toJsonValue(Object val) {
        if (val == null) {
            return JSONObject.NULL;
        }
        if (val instanceof Number || val instanceof Boolean || val instanceof String) {
            return val;
        }
        if (val instanceof Pair) {
            Pair<?, ?> p = (Pair<?, ?>) val;
            JSONArray arr = new JSONArray();
            arr.put(toJsonValue(p.first));
            arr.put(toJsonValue(p.second));
            return arr;
        }
        if (val instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<?>) val) {
                arr.put(toJsonValue(item));
            }
            return arr;
        }
        if (val instanceof int[]) {
            JSONArray arr = new JSONArray();
            for (int v : (int[]) val) {
                arr.put(v);
            }
            return arr;
        }
        if (val instanceof String[]) {
            JSONArray arr = new JSONArray();
            for (String s : (String[]) val) {
                arr.put(s);
            }
            return arr;
        }
        return String.valueOf(val);
    }
}
