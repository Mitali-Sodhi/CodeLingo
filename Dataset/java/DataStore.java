/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.higherfrequencytrading.chronicle.datamodel;

import com.higherfrequencytrading.chronicle.Chronicle;
import com.higherfrequencytrading.chronicle.Excerpt;
import com.higherfrequencytrading.chronicle.ExcerptMarshallable;
import com.higherfrequencytrading.chronicle.tcp.InProcessChronicleSink;
import com.higherfrequencytrading.chronicle.tools.ChronicleTools;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.Externalizable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

/**
 * @author peter.lawrey
 */
public class DataStore implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(DataStore.class.getName());
    protected final Map<String, Wrapper> wrappers = new ConcurrentHashMap<String, Wrapper>();
    @NotNull
    private final Chronicle chronicle;
    @NotNull
    private final ModelMode mode;
    @NotNull
    protected Wrapper[] wrappersArray = {};
    @Nullable
    private Excerpt excerpt = null;
    @Nullable
    private ExecutorService updater;
    private volatile boolean closed = false;

    public DataStore(@NotNull final Chronicle chronicle, @NotNull ModelMode mode) {
        this.chronicle = chronicle;
        this.mode = mode;

        switch (mode) {
            case MASTER:
                break;

            case READ_ONLY:
                final String name = chronicle.name();
                if (chronicle instanceof InProcessChronicleSink)
                    updater = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        @NotNull
                        @Override
                        public Thread newThread(@NotNull Runnable r) {
                            Thread t = new Thread(r, name + "data store updater");
                            t.setDaemon(true);
                            return t;
                        }
                    });
                else
                    updater = null;
                break;

            default:
                throw new IllegalArgumentException("Unknown mode " + mode);
        }
    }

    @SuppressWarnings("unchecked")
    public <Model> void inject(@NotNull Model model) {
        try {
            for (Class type = model.getClass(); type != null && type != Object.class && type != Enum.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    injectField(model, field);
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    public <Model> void injectField(Model model, @NotNull Field field) throws IllegalAccessException {
        if ((field.getModifiers() & Modifier.STATIC) != 0 || (field.getModifiers() & Modifier.TRANSIENT) != 0)
            return;

        field.setAccessible(true);
        Class<?> fieldType = field.getType();
        if (fieldType.isInterface()) {
            if (fieldType == Map.class || fieldType == ObservableMap.class) {
                Class[] genericTypes = ChronicleTools.getGenericTypes(field.getGenericType(), 2);
                Map underlying = (Map) field.get(model);
                if (underlying == null)
                    underlying = new ConcurrentHashMap();
                MapWrapper map = new MapWrapper(this, field.getName(), genericTypes[0], genericTypes[1], underlying, 1024);
                Annotation[] annotations = field.getAnnotations();
                if (annotations != null)
                    map.setAnnotations(annotations);
                field.set(model, map);

            } else if (fieldType == List.class || fieldType == ObservableList.class) {
                Class[] genericTypes = ChronicleTools.getGenericTypes(field.getGenericType(), 1);
                List underlying = (List) field.get(model);
                if (underlying == null)
                    underlying = Collections.synchronizedList(new ArrayList());
                ListWrapper list = new ListWrapper(this, field.getName(), genericTypes[0], underlying, 1024);
                Annotation[] annotations = field.getAnnotations();
                if (annotations != null)
                    list.setAnnotations(annotations);
                field.set(model, list);

            } else if (fieldType == Set.class || fieldType == ObservableSet.class) {
                Class[] genericTypes = ChronicleTools.getGenericTypes(field.getGenericType(), 1);
                Set underlying = (Set) field.get(model);
                if (underlying == null)
                    underlying = Collections.newSetFromMap(new ConcurrentHashMap());
                SetWrapper set = new SetWrapper(this, field.getName(), genericTypes[0], underlying, 1024);
                Annotation[] annotations = field.getAnnotations();
                if (annotations != null)
                    set.setAnnotations(annotations);
                field.set(model, set);

            } else {
                LOGGER.info("Skipping field of type " + fieldType + " as this is not supported interface");
            }
        } else {
            LOGGER.info("Skipping field of type " + fieldType + " as injecting concrete classes is not supported");
        }
    }

    public void start() {
        start(-1);
    }

    public void start(final long lastEvent) {
        switch (mode) {
            case MASTER:
                excerpt = chronicle.createExcerpt();
                long size = excerpt.size();
                while (excerpt.index() < size && excerpt.nextIndex()) {
                    processNextEvent(excerpt.index() <= lastEvent);
                }

                for (Wrapper wrapper : wrappersArray) {
                    wrapper.notifyOff(false);
                    wrapper.inSync();
                }
                break;

            case READ_ONLY:
                if (updater != null)
                    updater.submit(new Runnable() {
                        @Override
                        public void run() {
                            excerpt = chronicle.createExcerpt();
                            while (!closed) {
                                boolean found = excerpt.nextIndex();
                                if (found) {
                                    processNextEvent(excerpt.index() <= lastEvent);

                                } else {
                                    for (Wrapper wrapper : wrappersArray) {
                                        wrapper.notifyOff(false);
                                        wrapper.inSync();
                                    }
                                }
                            }
                        }
                    });
                break;

            default:
                throw new AssertionError("Unknown mode " + mode);
        }
    }

    boolean processNextEvent(boolean notifyOff) {
//        System.out.println(excerpt.index()+": "+ ChronicleTools.asString(excerpt));
        assert excerpt != null;
        String name = excerpt.readEnum(String.class);
        Wrapper wrapper = wrappers.get(name);
        if (wrapper == null)
            return true;
        wrapper.notifyOff(notifyOff);
        wrapper.onExcerpt(excerpt);
        excerpt.finish();
        return false;
    }

    public void startAtEnd() {
        start(chronicle.size() - 1);
    }

    public void add(String name, Wrapper wrapper) {
        wrappers.put(name, wrapper);
        wrappersArray = wrappers.values().toArray(new Wrapper[wrappers.size()]);
    }

    @NotNull
    public Excerpt startExcerpt(int capacity, @NotNull String name) {
        checkStarted();
        assert excerpt != null;
        excerpt.startExcerpt(capacity + 2 + name.length());
        excerpt.writeEnum(name);
        return excerpt;
    }

    private void checkStarted() {
        if (excerpt == null) throw new AssertionError("Not start()ed");
    }

    public boolean enumeratedClass(@NotNull Class eClass) {
        if (Comparable.class.isAssignableFrom(eClass) && (eClass.getModifiers() & Modifier.FINAL) != 0)
            return true;
        if (ExcerptMarshallable.class.isAssignableFrom(eClass) || Externalizable.class.isAssignableFrom(eClass))
            return true;
        return chronicle.getMarshaller(eClass) != null;
    }

    public void checkWritable() {
        if (!mode.writable) throw new IllegalStateException("ModelModel=" + mode);
    }

    public long events() {
        checkStarted();
        assert excerpt != null;
        return excerpt.index() + 1;
    }

    public void close() {
        closed = true;
        if (updater != null)
            updater.shutdown();
        chronicle.close();
    }

    /**
     * Should only be used for plain IndexChronicle.
     *
     * @return was a new entry found.
     */
    public boolean nextEvent() {
        assert updater == null;
        assert excerpt != null;
        if (excerpt.nextIndex()) {
            processNextEvent(false);
            return true;
        }
        return false;
    }
}
