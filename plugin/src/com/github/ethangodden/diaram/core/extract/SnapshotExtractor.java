package com.github.ethangodden.diaram.core.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaPrimitiveValue;
import org.eclipse.jdt.debug.core.IJavaReferenceType;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;

import com.github.ethangodden.diaram.core.ExtractionLimits;
import com.github.ethangodden.diaram.model.MemorySnapshot;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.diaram.model.MemorySnapshot.Value;

/**
 * Walks a suspended thread and produces one immutable {@link MemorySnapshot} by driving a
 * {@link MemorySnapshot.Builder}. This is the ONLY class in the plugin that makes JDI wire calls; it
 * must run on a Jobs-framework worker thread, never on the UI or debug event dispatch thread. One
 * instance per extraction run.
 *
 * <p>The JDI walk is unchanged in shape from the old model-building extractor — frames (this +
 * locals), statics, and a stub-first BFS over the heap with caps from {@link ExtractionLimits} — but
 * every production point now emits neutral {@link DisplayableStruct}s
 * and {@link DisplayableVariable} rows through the builder instead of language-shaped model records.
 *
 * <p>Failure handling is layered: a per-value read failure degrades to {@code Primitive("?")}; a
 * per-object failure leaves the already-reserved stub box in place (arrows stay valid); a per-frame
 * failure yields a body-only placeholder frame; an unusable thread/target aborts the whole walk with
 * {@link OperationCanceledException}.
 */
public final class SnapshotExtractor {

    private static final String STRING_SIGNATURE = "Ljava/lang/String;"; //$NON-NLS-1$

    // A String whose backing array is larger than this is NOT pulled over JDWP in
    // full: getValueString() would transfer and allocate the entire contents on the
    // debugger before truncation to maxStringLength, so a pathological debuggee String
    // could stall or OOM the debugger despite the cap. Bounds transient/wire cost the
    // way maxStringLength bounds the stored text; over-ceiling strings show a marker.
    private static final int MAX_STRING_TRANSFER_CHARS = 1 << 20; // ~1M

    private static final Map<String, String> BOXED_BY_SIGNATURE = Map.of(
            "Ljava/lang/Boolean;", "java.lang.Boolean", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Byte;", "java.lang.Byte", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Character;", "java.lang.Character", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Short;", "java.lang.Short", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Integer;", "java.lang.Integer", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Long;", "java.lang.Long", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Float;", "java.lang.Float", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Double;", "java.lang.Double"); //$NON-NLS-1$ //$NON-NLS-2$

    @FunctionalInterface
    private interface DebugRead<T> {
        T read() throws DebugException;
    }

    private record Pending(IJavaObject object, long id, int depth, String typeName) {
    }

    private final ExtractionLimits limits;
    private final IProgressMonitor monitor;

    private IJavaThread thread;
    private IDebugTarget target;

    // The builder is the sole sink; it owns struct discovery order (first reserve/fill). The Builder
    // exposes no membership query and reserve() is a silent no-op on repeats, so reservedBoxes is
    // what ensures each object is enqueued for exploration (and its type name fetched over the
    // wire) exactly once.
    private MemorySnapshot.Builder builder;
    private final List<DisplayableFrame> frames = new ArrayList<>();
    private final Set<Long> reservedBoxes = new HashSet<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final LinkedHashMap<String, IJavaReferenceType> staticsTypes = new LinkedHashMap<>();
    private int admitted;       // enqueued for exploration; counts toward maxObjects

    public SnapshotExtractor(ExtractionLimits limits, IProgressMonitor monitor) {
        this.limits = limits;
        this.monitor = monitor;
    }

    public MemorySnapshot extract(IJavaThread javaThread,
            String debugTargetToken, String threadToken) throws DebugException {
        this.thread = javaThread;
        this.target = javaThread.getDebugTarget();
        abortIfUnusable();
        checkCanceled();

        String threadName;
        try {
            threadName = javaThread.getName();
        } catch (DebugException e) {
            threadName = "?"; //$NON-NLS-1$
        }

        // targetId is baked into every minted reference token, so consecutive snapshots of the
        // same target resolve each other's references while other targets' miss.
        this.builder = MemorySnapshot.builder(debugTargetToken);

        IStackFrame[] raw = javaThread.getStackFrames(); // one wire call, top frame first

        // Discover the statics classes (from non-obsolete frames) BEFORE any value conversion so we
        // can reserve their heap structs first — this keeps statics at the top of the heap column
        // even though frame-variable references reserve object stubs during frame extraction.
        for (IStackFrame frame : raw) {
            IJavaStackFrame javaFrame = (IJavaStackFrame) frame;
            if (javaFrame.isObsolete()) {
                registerStaticsType(javaFrame);
            }
        }
        for (String className : staticsTypes.keySet()) {
            builder.reserve(staticsBoxToken(className), staticsHeader(className));
        }

        // Frames render top-of-stack first; depthFromBottom numbers from the bottom.
        for (int i = 0; i < raw.length; i++) {
            checkCanceled();
            IJavaStackFrame frame = (IJavaStackFrame) raw[i];
            extractFrame(frame, raw.length - 1 - i);
        }

        // Fill the statics structs (reserved above, so they hold their top-of-column discovery
        // order) BEFORE draining regular objects. Statics are BFS roots (depth 0); their field
        // values reserve object stubs after the statics.
        fillStatics();
        drainHeapQueue();

        // The walk suspends exactly one thread; monitors/contention are not captured (yet).
        builder.thread(new DisplayableThread(threadToken, threadName, "suspended", frames, null)); //$NON-NLS-1$
        return builder.build();
    }

    // ---- stack -----------------------------------------------------------

    private void extractFrame(IJavaStackFrame frame, int depthFromBottom) {
        boolean obsolete = readOr(frame::isObsolete, Boolean.TRUE).booleanValue();
        String typeName = readOr(frame::getDeclaringTypeName, "<unknown>"); //$NON-NLS-1$
        String methodName = readOr(frame::getMethodName, "<unknown>"); //$NON-NLS-1$
        String signature = readOr(frame::getSignature, ""); //$NON-NLS-1$
        int lineNumber = readOr(frame::getLineNumber, Integer.valueOf(-1)).intValue();
        boolean nativeFrame = readOr(frame::isNative, Boolean.FALSE).booleanValue();
        boolean staticMethod = readOr(frame::isStatic, Boolean.FALSE).booleanValue();
        String frameToken = frameKey(depthFromBottom, typeName, methodName, signature);
        String header = buildLabel(typeName, methodName, lineNumber);

        if (!obsolete) {
            try {
                completeFrame(frame, frameToken, typeName, header, nativeFrame, staticMethod);
                return;
            } catch (DebugException e) {
                // Per-frame degradation (incl. ERR_INVALID_STACK_FRAME): note-only placeholder, walk survives.
                abortIfUnusable();
                noteFrame(frameToken, header, shortMessage(e));
                return;
            }
        }
        noteFrame(frameToken, header, "(obsolete method)"); //$NON-NLS-1$
    }

    /** A note-only frame: the note shows in place of variable rows. */
    private void noteFrame(String id, String label, String note) {
        frames.add(new DisplayableFrame(id, label, List.of(), note));
    }

    private void completeFrame(IJavaStackFrame frame, String frameToken, String typeName,
            String header, boolean nativeFrame, boolean staticMethod) throws DebugException {

        List<DisplayableVariable> variables = new ArrayList<>();

        DisplayableVariable thisVariable = null;
        try {
            IJavaObject self = frame.getThis(); // null for static/native frames
            if (self != null) {
                thisVariable = new DisplayableVariable("this", typeName, convert(self, 0)); //$NON-NLS-1$
            }
        } catch (DebugException e) {
            abortIfUnusable();
            if (!staticMethod && !nativeFrame) {
                thisVariable = new DisplayableVariable("this", typeName, new Value.BoxValue("?")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (thisVariable != null) {
            variables.add(thisVariable);
        }

        IJavaVariable[] rawLocals = frame.getLocalVariables(); // slot/declaration order
        for (IJavaVariable variable : rawLocals) {
            String name = readOr(variable::getName, null);
            if (name == null) {
                continue;
            }
            variables.add(new DisplayableVariable(name, declaredTypeName(variable), valueOf(variable, 0)));
        }

        if (variables.isEmpty()) {
            // No receiver and no locals: a note-only placeholder, as the old model rendered.
            noteFrame(frameToken, header, nativeFrame ? "(native method)" : "(no variables)"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        frames.add(new DisplayableFrame(frameToken, header, variables, null));
    }

    private static String buildLabel(String typeName, String methodName, int lineNumber) {
        StringBuilder label = new StringBuilder();
        label.append(simpleName(typeName)).append('.').append(methodName).append("()"); //$NON-NLS-1$
        if (lineNumber >= 0) {
            label.append(" line ").append(lineNumber); //$NON-NLS-1$
        }
        return label.toString();
    }

    // ---- values ----------------------------------------------------------

    /** Converts a value, reserving any referenced object's box (stub first). */
    private Value convert(IJavaValue value, int depth) {
        try {
            if (value == null || value.isNull()) {
                // The debuggee's null: shown as text, the row keeps the declared type.
                return new Value.BoxValue("null"); //$NON-NLS-1$
            }
            if (value instanceof IJavaPrimitiveValue) {
                return new Value.BoxValue(value.getValueString());
            }
            if (value instanceof IJavaArray array) {
                return reference(array, depth);
            }
            if (value instanceof IJavaObject object) {
                String signature = object.getSignature();
                if (STRING_SIGNATURE.equals(signature) && limits.inlineStrings()) {
                    // Escape hatch: no heap box, quoted text inline.
                    return new Value.BoxValue(quotedString(object));
                }
                String wrapper = BOXED_BY_SIGNATURE.get(signature);
                if (wrapper != null && limits.inlineBoxed()) {
                    return new Value.BoxValue(boxedText(object));
                }
                return reference(object, depth);
            }
            return new Value.BoxValue(value.getValueString()); // JDI void and friends
        } catch (DebugException e) {
            abortIfUnusable();
            return new Value.BoxValue("?"); //$NON-NLS-1$
        }
    }

    private Value reference(IJavaObject object, int depth) throws DebugException {
        long id = object.getUniqueId();
        if (id == -1) {
            // Collected between value fetch and id fetch: no box.
            abortIfUnusable();
            return new Value.BoxValue("?"); //$NON-NLS-1$
        }
        if (reservedBoxes.add(id)) {
            // STUB first: every reference has a target struct, aliasing stays correct past the
            // caps, and cycles terminate trivially. reserve claims the struct's discovery slot.
            String typeName = typeNameOf(object);
            builder.reserve(boxToken(id), simpleName(typeName) + " #" + id); //$NON-NLS-1$
            if (depth < limits.maxDepth() && admitted < limits.maxObjects()) {
                admitted++;
                queue.add(new Pending(object, id, depth, typeName));
            }
        }
        return builder.reference(boxToken(id));
    }

    // ---- heap ------------------------------------------------------------

    private void drainHeapQueue() {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            checkCanceled();
            buildNode(pending); // fills the reserved box in place, keeping BFS slot order
        }
    }

    /** Fills {@code id}'s struct: always explored; monitors are not captured (yet). */
    private void fill(String id, String type, List<DisplayableVariable> rows, int omitted) {
        builder.fill(new DisplayableStruct(id, type, rows, true, omitted, null));
    }

    private void buildNode(Pending pending) {
        String simple = simpleName(pending.typeName());
        try {
            if (pending.object() instanceof IJavaArray array) {
                buildArray(array, pending);
                return;
            }
            String signature = pending.object().getSignature();
            if (STRING_SIGNATURE.equals(signature)) {
                buildString(pending.object(), pending.id(), simple);
                return;
            }
            String wrapper = BOXED_BY_SIGNATURE.get(signature);
            if (wrapper != null) {
                buildBoxed(pending.object(), pending.id(), wrapper);
                return;
            }
            buildPlainOrEnum(pending, simple);
        } catch (DebugException e) {
            // Per-object degradation: the reserved stub box stays, so inbound arrows remain valid.
            abortIfUnusable();
        }
    }

    private void buildArray(IJavaArray array, Pending pending) throws DebugException {
        int length = array.getLength();
        int shown = Math.min(length, limits.maxArrayElements());
        String componentType = componentTypeName(pending.typeName());
        List<DisplayableVariable> fields = new ArrayList<>(shown);
        if (length <= limits.maxArrayElements()) {
            IJavaValue[] values = array.getValues(); // one JDWP round trip for the whole array
            for (int i = 0; i < values.length; i++) {
                fields.add(arrayElement(i, componentType, convert(values[i], pending.depth() + 1)));
            }
        } else {
            // Bounded per-index reads; never materialize a huge array wholesale.
            for (int i = 0; i < shown; i++) {
                checkCanceled();
                Value element;
                try {
                    element = convert(array.getValue(i), pending.depth() + 1);
                } catch (DebugException e) {
                    abortIfUnusable();
                    element = new Value.BoxValue("?"); //$NON-NLS-1$
                }
                fields.add(arrayElement(i, componentType, element));
            }
        }
        // Header: componentBase + "[" + length + "] #" + id.
        String header = componentBase(pending.typeName()) + "[" + length + "] #" + pending.id(); //$NON-NLS-1$ //$NON-NLS-2$
        fill(boxToken(pending.id()), header, fields, length - shown);
    }

    /** An array element row: positional label, component type in the type label. */
    private static DisplayableVariable arrayElement(int index, String componentType, Value value) {
        return new DisplayableVariable(Integer.toString(index), componentType, value);
    }

    private void buildString(IJavaObject object, long id, String simple) throws DebugException {
        String header = simple + " #" + id; //$NON-NLS-1$
        CappedText capped = readCappedString(object);
        if (capped == null) {
            // Too large to pull over the wire: no characters, single omitted marker.
            fill(boxToken(id), header, List.of(), 1);
            return;
        }
        String text = capped.text();
        List<DisplayableVariable> fields = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            // One field per char: label is the index, value is the character text.
            fields.add(new DisplayableVariable(Integer.toString(i), "char", //$NON-NLS-1$
                    new Value.BoxValue(String.valueOf(text.charAt(i)))));
        }
        fill(boxToken(id), header, fields, capped.truncated() ? 1 : 0);
    }

    /** Capped raw contents of a String plus whether the cap bit, or null if too large to pull over the wire. */
    private CappedText readCappedString(IJavaObject object) throws DebugException {
        if (exceedsTransferCeiling(object)) {
            return null;
        }
        String text = object.getValueString(); // raw contents of the StringReference
        if (text == null) {
            text = ""; //$NON-NLS-1$
        }
        boolean truncated = text.length() > limits.maxStringLength();
        return new CappedText(StringUtils.truncate(text, limits.maxStringLength()), truncated);
    }

    private record CappedText(String text, boolean truncated) {}

    private void buildBoxed(IJavaObject object, long id, String wrapperTypeName)
            throws DebugException {
        IJavaValue inner = boxedInner(object);
        String text = boxedText(inner);
        if (isJvmCached(wrapperTypeName, inner)) {
            text = text + " (JVM cache)"; //$NON-NLS-1$
        }
        String header = simpleName(wrapperTypeName) + " #" + id; //$NON-NLS-1$
        // A boxed primitive is a single field carrying the (possibly cache-annotated) display value.
        DisplayableVariable field = new DisplayableVariable("value", wrapperTypeName, new Value.BoxValue(text)); //$NON-NLS-1$
        fill(boxToken(id), header, List.of(field), 0);
    }

    /** Spec-defined valueOf caches: Integer/Short/Byte/Long in [-128,127], Character in [0,127], Boolean always. */
    private static boolean isJvmCached(String wrapperTypeName, IJavaValue inner) {
        if ("java.lang.Boolean".equals(wrapperTypeName)) { //$NON-NLS-1$
            return true;
        }
        if (!(inner instanceof IJavaPrimitiveValue primitive)) {
            return false;
        }
        return switch (wrapperTypeName) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte" -> { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                long value = primitive.getLongValue();
                yield value >= -128 && value <= 127;
            }
            case "java.lang.Character" -> primitive.getCharValue() <= 127; //$NON-NLS-1$
            default -> false;
        };
    }

    private void buildPlainOrEnum(Pending pending, String simple) throws DebugException {
        IJavaObject object = pending.object();
        boolean isEnum = false;
        try {
            isEnum = object.getJavaType() instanceof IJavaClassType classType && classType.isEnum();
        } catch (DebugException e) {
            abortIfUnusable();
        }

        IVariable[] variables = object.getVariables(); // allFields(): instance+static+inherited, JDT-sorted
        List<DisplayableVariable> fields = new ArrayList<>();
        int omitted = 0;
        String enumConstantName = null;
        for (IVariable raw : variables) {
            if (!(raw instanceof IJavaVariable variable)) {
                continue;
            }
            boolean isStatic;
            boolean isSynthetic;
            try {
                isStatic = variable.isStatic();
                isSynthetic = variable.isSynthetic();
            } catch (DebugException e) {
                abortIfUnusable();
                continue;
            }
            if (isStatic) {
                continue; // statics render in the STATIC DATA area, not the object box
            }
            if (isSynthetic && !limits.includeSyntheticFields()) {
                continue;
            }
            String name = readOr(variable::getName, null);
            if (name == null) {
                continue;
            }
            if (isEnum && enumConstantName == null && "name".equals(name) && isEnumBaseField(variable)) { //$NON-NLS-1$
                // Inherited private java.lang.Enum.name — only visible via allFields().
                enumConstantName = readOr(() -> variable.getValue().getValueString(), null);
            }
            if (fields.size() >= limits.maxFieldsPerObject()) {
                omitted++;
                continue;
            }
            fields.add(toFieldVariable(variable, name, pending.depth() + 1));
        }

        String header = simple + " #" + pending.id(); //$NON-NLS-1$
        List<DisplayableVariable> boxFields;
        if (isEnum && enumConstantName != null) {
            // A synthetic leading box-only field: label is the constant name, value unused.
            boxFields = new ArrayList<>(fields.size() + 1);
            // type is null (not "") so the renderer recognizes this as a box-only row.
            boxFields.add(new DisplayableVariable(enumConstantName, null, new Value.BoxValue("null"))); //$NON-NLS-1$
            boxFields.addAll(fields);
        } else {
            boxFields = fields;
        }
        fill(boxToken(pending.id()), header, boxFields, omitted);
    }

    private static boolean isEnumBaseField(IJavaVariable variable) {
        if (variable instanceof IJavaFieldVariable field) {
            try {
                IJavaType declaring = field.getDeclaringType();
                return declaring != null && "java.lang.Enum".equals(declaring.getName()); //$NON-NLS-1$
            } catch (DebugException e) {
                return false;
            }
        }
        return false;
    }

    /** A field row: its diff identity is the label plus its stable position among same-named
     * (shadowed) fields — JDT's allFields() order is stable across suspends. */
    private DisplayableVariable toFieldVariable(IJavaVariable variable, String name, int depth) {
        return new DisplayableVariable(name, declaredTypeName(variable), valueOf(variable, depth));
    }

    // ---- statics ---------------------------------------------------------

    private void registerStaticsType(IJavaStackFrame frame) {
        try {
            IJavaReferenceType type = frame.getReferenceType();
            if (type != null) {
                staticsTypes.putIfAbsent(type.getName(), type); // first appearance = top frame first
            }
        } catch (DebugException e) {
            abortIfUnusable();
        }
    }

    private void fillStatics() {
        for (Map.Entry<String, IJavaReferenceType> entry : staticsTypes.entrySet()) {
            checkCanceled();
            String className = entry.getKey();
            IJavaReferenceType type = entry.getValue();
            List<DisplayableVariable> fields = new ArrayList<>();
            int omitted = 0;
            try {
                // Declared fields only: inherited statics show under their own class
                // when that class is also on the stack.
                for (String name : type.getDeclaredFieldNames()) {
                    IJavaFieldVariable field;
                    try {
                        field = type.getField(name);
                    } catch (DebugException e) {
                        abortIfUnusable();
                        continue;
                    }
                    if (field == null) {
                        continue;
                    }
                    boolean isStatic;
                    boolean isSynthetic;
                    try {
                        isStatic = field.isStatic();
                        isSynthetic = field.isSynthetic();
                    } catch (DebugException e) {
                        abortIfUnusable();
                        continue;
                    }
                    if (!isStatic || (isSynthetic && !limits.includeSyntheticFields())) {
                        continue;
                    }
                    if (fields.size() >= limits.maxStaticFieldsPerClass()) {
                        omitted++;
                        continue;
                    }
                    // Statics are BFS roots (depth 0).
                    fields.add(new DisplayableVariable(name, declaredTypeName(field), valueOf(field, 0)));
                }
            } catch (DebugException e) {
                abortIfUnusable();
            }
            // The struct was reserved in extract(); fill it (even when empty) so it holds its
            // top-of-column position. An empty statics struct carries no fields.
            fill(staticsBoxToken(className), staticsHeader(className), fields, omitted);
        }
    }

    private static String staticsBoxToken(String className) {
        return "statics:" + className; //$NON-NLS-1$
    }

    private static String staticsHeader(String className) {
        return "Class " + simpleName(className); //$NON-NLS-1$
    }

    // ---- helpers ---------------------------------------------------------

    private static String boxToken(long id) {
        return Long.toString(id);
    }

    /** Frame diff identity: numbered from the stack BOTTOM so surviving frames keep their tokens. */
    static String frameKey(int depthFromBottom, String declaringTypeName, String methodName, String signature) {
        return depthFromBottom + "|" + declaringTypeName + "." + methodName + signature; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Value valueOf(IJavaVariable variable, int depth) {
        try {
            return convert((IJavaValue) variable.getValue(), depth);
        } catch (DebugException e) {
            abortIfUnusable();
            return new Value.BoxValue("?"); //$NON-NLS-1$
        }
    }

    private String declaredTypeName(IJavaVariable variable) {
        try {
            return variable.getReferenceTypeName();
        } catch (DebugException e) {
            try {
                return typeNameFromSignature(variable.getSignature());
            } catch (DebugException e2) {
                return "?"; //$NON-NLS-1$
            }
        }
    }

    private String typeNameOf(IJavaValue value) {
        try {
            IJavaType type = value.getJavaType();
            if (type != null) {
                return type.getName();
            }
        } catch (DebugException e) {
            // fall through to the reference type name
        }
        try {
            return value.getReferenceTypeName();
        } catch (DebugException e) {
            return "?"; //$NON-NLS-1$
        }
    }

    /** The declared type of an array, minus one dimension (its element/component type). */
    private static String componentTypeName(String arrayTypeName) {
        if (arrayTypeName != null && arrayTypeName.endsWith("[]")) { //$NON-NLS-1$
            return arrayTypeName.substring(0, arrayTypeName.length() - 2);
        }
        return arrayTypeName == null ? "?" : arrayTypeName; //$NON-NLS-1$
    }

    /** The innermost (dimension-stripped) base of an array type, for the "base[len]" header. */
    private static String componentBase(String arrayTypeName) {
        String base = arrayTypeName == null ? "?" : arrayTypeName; //$NON-NLS-1$
        while (base.endsWith("[]")) { //$NON-NLS-1$
            base = base.substring(0, base.length() - 2);
        }
        return simpleName(base);
    }

    private String quotedString(IJavaObject object) throws DebugException {
        CappedText capped = readCappedString(object);
        if (capped == null) {
            return "\"...\""; // too large to pull over the wire //$NON-NLS-1$
        }
        String ellipsis = capped.truncated() ? "..." : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return "\"" + capped.text() + ellipsis + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * True when the String's backing array is so large that pulling its full contents
     * over JDWP (getValueString) would risk an allocation/transfer spike on the debugger.
     * Reads only the backing-array length, never its contents. Best-effort: any probe
     * failure returns false so the normal full read still runs.
     */
    private static boolean exceedsTransferCeiling(IJavaObject stringObject) {
        try {
            IJavaFieldVariable valueField = stringObject.getField("value", false); //$NON-NLS-1$
            if (valueField != null && valueField.getValue() instanceof IJavaArray backing) {
                return backing.getLength() > MAX_STRING_TRANSFER_CHARS;
            }
        } catch (DebugException e) {
            // best-effort probe: fall back to the normal full read
        }
        return false;
    }

    private static String boxedText(IJavaObject object) throws DebugException {
        return boxedText(boxedInner(object));
    }

    private static String boxedText(IJavaValue inner) throws DebugException {
        return inner == null ? "?" : inner.getValueString(); //$NON-NLS-1$
    }

    /** The unwrapped {@code value} field of a boxed primitive (null if the field is absent). */
    private static IJavaValue boxedInner(IJavaObject object) throws DebugException {
        IJavaFieldVariable valueField = object.getField("value", false); //$NON-NLS-1$
        return valueField == null ? null : (IJavaValue) valueField.getValue();
    }

    static String typeNameFromSignature(String signature) {
        if (StringUtils.isEmpty(signature)) {
            return "?"; //$NON-NLS-1$
        }
        int dims = 0;
        while (dims < signature.length() && signature.charAt(dims) == '[') {
            dims++;
        }
        if (dims >= signature.length()) {
            return "?"; //$NON-NLS-1$
        }
        String base = switch (signature.charAt(dims)) {
            case 'B' -> "byte"; //$NON-NLS-1$
            case 'C' -> "char"; //$NON-NLS-1$
            case 'D' -> "double"; //$NON-NLS-1$
            case 'F' -> "float"; //$NON-NLS-1$
            case 'I' -> "int"; //$NON-NLS-1$
            case 'J' -> "long"; //$NON-NLS-1$
            case 'S' -> "short"; //$NON-NLS-1$
            case 'Z' -> "boolean"; //$NON-NLS-1$
            case 'V' -> "void"; //$NON-NLS-1$
            case 'L' -> signature.endsWith(";") //$NON-NLS-1$
                    ? signature.substring(dims + 1, signature.length() - 1).replace('/', '.')
                    : "?"; //$NON-NLS-1$
            default -> "?"; //$NON-NLS-1$
        };
        return base + "[]".repeat(dims); //$NON-NLS-1$
    }

    static String simpleName(String typeName) {
        if (StringUtils.isEmpty(typeName)) {
            return "?"; //$NON-NLS-1$
        }
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }

    private static String shortMessage(DebugException e) {
        String message = e.getStatus() != null ? e.getStatus().getMessage() : null;
        if (StringUtils.isBlank(message)) {
            message = e.getClass().getSimpleName();
        }
        return StringUtils.truncate(message.trim(), 120);
    }

    private <T> T readOr(DebugRead<T> read, T fallback) {
        try {
            return read.read();
        } catch (DebugException e) {
            abortIfUnusable();
            return fallback;
        }
    }

    /**
     * Bail out of a doomed walk. Called from every read's catch block: a walk whose
     * thread resumed or whose VM went away fails on every read, so abort fast instead
     * of grinding through the caps. These probes are local state checks (no wire calls).
     */
    private void abortIfUnusable() {
        if (target == null || target.isTerminated() || target.isDisconnected()
                || !thread.isSuspended()) {
            throw new OperationCanceledException();
        }
    }

    private void checkCanceled() {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }
}
