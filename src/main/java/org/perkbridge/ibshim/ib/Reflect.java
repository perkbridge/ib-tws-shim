// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim.ib;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Reflection helpers for the IB client library. The adapter reaches IB's
 * classes only by name at run time, so this repository needs no IB file to
 * compile and survives the library's usual signature drift (for example
 * {@code cancelOrder(int, String)} versus {@code cancelOrder(int, OrderCancel)}).
 */
final class Reflect {
    private Reflect() {}

    /** A call that the loaded library does not offer in any usable form. */
    static final class MissingMemberException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MissingMemberException(String m) {
            super(m);
        }
    }

    static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == boolean.class) return Boolean.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    /** Adapts a value to a parameter type, or returns {@code NO_MATCH}. */
    private static final Object NO_MATCH = new Object();

    private static Object adapt(Object v, Class<?> want) {
        Class<?> w = boxed(want);
        if (v == null) {
            return want.isPrimitive() ? NO_MATCH : null;
        }
        if (w.isInstance(v)) {
            return v;
        }
        if (v instanceof Number n) {
            if (w == Integer.class && n.longValue() >= Integer.MIN_VALUE && n.longValue() <= Integer.MAX_VALUE
                    && !(v instanceof Double) && !(v instanceof BigDecimal)) {
                return n.intValue();
            }
            if (w == Long.class && !(v instanceof Double) && !(v instanceof BigDecimal)) {
                return n.longValue();
            }
            if (w == Double.class) {
                return n.doubleValue();
            }
        }
        if (v instanceof String s) {
            // a String can fill an enum parameter (by API string or constant name)
            if (w.isEnum()) {
                Object e = enumFor(w, s);
                return e == null ? NO_MATCH : e;
            }
            // or a value class with a static parse(String), e.g. Decimal
            try {
                Method m = w.getMethod("parse", String.class);
                if (Modifier.isStatic(m.getModifiers()) && w.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null, s);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // not parseable that way
            }
        }
        return NO_MATCH;
    }

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    static Object enumFor(Class<?> enumClass, String text) {
        String want = norm(text);
        for (Object c : enumClass.getEnumConstants()) {
            if (norm(c.toString()).equals(want) || norm(((Enum<?>) c).name()).equals(want)
                    || norm(String.valueOf(apiString(c))).equals(want)) {
                return c;
            }
        }
        return null;
    }

    /** The text form IB uses for an enum-like value. */
    static String apiString(Object o) {
        if (o == null) {
            return null;
        }
        try {
            Method m = o.getClass().getMethod("getApiString");
            Object r = m.invoke(o);
            if (r != null) {
                return r.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // plain value
        }
        return o instanceof Enum<?> e ? e.toString() : o.toString();
    }

    private static Object[] adaptAll(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return null;
        }
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = adapt(args[i], types[i]);
            if (a == NO_MATCH) {
                return null;
            }
            out[i] = a;
        }
        return out;
    }

    /** Calls the first public method named {@code name} that accepts the arguments. */
    static Object call(Object target, String name, Object... args) {
        Class<?> c = target.getClass();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Object[] a = adaptAll(m.getParameterTypes(), args);
            if (a == null) {
                continue;
            }
            return invoke(m, target, a);
        }
        throw new MissingMemberException(c.getName() + "." + name + "/" + args.length + " not found");
    }

    static boolean has(Object target, String name, int arity) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Method m, Object target, Object[] a) {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause() == null ? e : e.getCause();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(t);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object newInstance(Class<?> c, Object... args) {
        for (java.lang.reflect.Constructor<?> k : c.getConstructors()) {
            Object[] a = adaptAll(k.getParameterTypes(), args);
            if (a == null) {
                continue;
            }
            try {
                return k.newInstance(a);
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause() == null ? e : e.getCause();
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(t);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new MissingMemberException(c.getName() + " constructor/" + args.length + " not found");
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Reads a property: {@code getX()}, {@code x()}, then a public field {@code x} or {@code m_x}. */
    static Object get(Object bean, String prop) {
        if (bean == null) {
            return null;
        }
        Class<?> c = bean.getClass();
        for (String n : new String[] {"get" + cap(prop), prop}) {
            try {
                Method m = c.getMethod(n);
                if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() != void.class) {
                    return m.invoke(bean);
                }
            } catch (NoSuchMethodException ignored) {
                // try the next form
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        for (String n : new String[] {prop, "m_" + prop}) {
            try {
                Field f = c.getField(n);
                return f.get(bean);
            } catch (NoSuchFieldException ignored) {
                // try the next form
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    /** Writes a property: {@code x(v)}, {@code setX(v)}, then a public field. */
    static void set(Object bean, String prop, Object value) {
        Class<?> c = bean.getClass();
        for (String n : new String[] {prop, "set" + cap(prop)}) {
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(n) || m.getParameterCount() != 1 || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Object a = adapt(value, m.getParameterTypes()[0]);
                if (a != NO_MATCH) {
                    invoke(m, bean, new Object[] {a});
                    return;
                }
            }
        }
        for (String n : new String[] {prop, "m_" + prop}) {
            try {
                Field f = c.getField(n);
                Object a = adapt(value, f.getType());
                if (a != NO_MATCH) {
                    f.set(bean, a);
                    return;
                }
            } catch (NoSuchFieldException ignored) {
                // try the next form
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new MissingMemberException(c.getName() + " has no way to set " + prop);
    }
}
