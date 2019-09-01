package de.gsi.chart.plugins;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.controlsfx.control.RangeSlider;
import org.controlsfx.glyphfont.Glyph;

import de.gsi.chart.Chart;
import de.gsi.chart.axes.Axis;
import de.gsi.chart.axes.AxisMode;
import de.gsi.chart.axes.spi.Axes;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Zoom capabilities along X, Y or both axis. For every zoom-in operation the
 * current X and Y range is remembered and restored upon following zoom-out
 * operation.
 * <ul>
 * <li>zoom-in - triggered on {@link MouseEvent#MOUSE_PRESSED MOUSE_PRESSED}
 * event that is accepted by {@link #getZoomInMouseFilter() zoom-in filter}. It
 * shows a zooming rectangle determining the zoom window once mouse button is
 * released.</li>
 * <li>zoom-out - triggered on {@link MouseEvent#MOUSE_CLICKED MOUSE_CLICKED}
 * event that is accepted by {@link #getZoomOutMouseFilter() zoom-out filter}.
 * It restores the previous ranges on both axis.</li>
 * <li>zoom-origin - triggered on {@link MouseEvent#MOUSE_CLICKED MOUSE_CLICKED}
 * event that is accepted by {@link #getZoomOriginMouseFilter() zoom-origin
 * filter}. It restores the initial ranges on both axis as it was at the moment
 * of the first zoom-in operation.</li>
 * </ul>
 * <p>
 * CSS class name of the zoom rectangle: {@value #STYLE_CLASS_ZOOM_RECT}.
 * </p>
 *
 * @author Grzegorz Kruk
 * @author rstein - adapted to XYChartPane, corrected some features (mouse zoom
 *         events outside canvas, auto-ranging on zoom-out, scrolling, toolbar)
 */
public class Zoomer extends ChartPlugin {
    private static final String FONT_AWESOME = "FontAwesome";
    private static final String ZOOMER_OMIT_AXIS = "OmitAxisZoom";
    /**
     * Name of the CCS class of the zoom rectangle.
     */
    public static final String STYLE_CLASS_ZOOM_RECT = "chart-zoom-rect";
    private static final int ZOOM_RECT_MIN_SIZE = 5;
    private static final Duration DEFAULT_ZOOM_DURATION = Duration.millis(500);
    private static final int FONT_SIZE = 20;

    /**
     * Default zoom-in mouse filter passing on left mouse button (only).
     */
    public final Predicate<MouseEvent> defaultZoomInMouseFilter = event -> MouseEventsHelper.isOnlyPrimaryButtonDown(
            event) && MouseEventsHelper.modifierKeysUp(event) && isMouseEventWithinCanvas(event);

    /**
     * Default zoom-out mouse filter passing on right mouse button (only).
     */
    public final Predicate<MouseEvent> defaultZoomOutMouseFilter = event -> MouseEventsHelper.isOnlySecondaryButtonDown(
            event) && MouseEventsHelper.modifierKeysUp(event) && isMouseEventWithinCanvas(event);

    /**
     * Default zoom-origin mouse filter passing on right mouse button with
     * {@link MouseEvent#isControlDown() control key down}.
     */
    public final Predicate<MouseEvent> defaultZoomOriginFilter = event -> MouseEventsHelper.isOnlySecondaryButtonDown(
            event) && MouseEventsHelper.isOnlyCtrlModifierDown(event) && isMouseEventWithinCanvas(event);

    /**
     * Default zoom scroll filter with {@link MouseEvent#isControlDown() control
     * key down}.
     */
    public final Predicate<ScrollEvent> defaultScrollFilter = this::isMouseEventWithinCanvas;

    private Predicate<MouseEvent> zoomInMouseFilter = defaultZoomInMouseFilter;
    private Predicate<MouseEvent> zoomOutMouseFilter = defaultZoomOutMouseFilter;
    private Predicate<MouseEvent> zoomOriginMouseFilter = defaultZoomOriginFilter;
    private Predicate<ScrollEvent> zoomScrollFilter = defaultScrollFilter;

    private final Rectangle zoomRectangle = new Rectangle();
    private Point2D zoomStartPoint = null;
    private Point2D zoomEndPoint = null;
    private final Deque<Map<Axis, ZoomState>> zoomStacks = new ArrayDeque<>();
    private final HBox zoomButtons = getZoomInteractorBar();
    private ZoomRangeSlider xRangeSlider;
    private boolean xRangeSliderInit;
    private ObservableList<Axis> omitAxisZoom = FXCollections.observableArrayList();

    private final ObjectProperty<AxisMode> axisMode = new SimpleObjectProperty<AxisMode>(this, "axisMode",
            AxisMode.XY) {
        @Override
        protected void invalidated() {
            Objects.requireNonNull(get(), "The " + getName() + " must not be null");
        }
    };

    private Cursor originalCursor;

    private final ObjectProperty<Cursor> dragCursor = new SimpleObjectProperty<>(this, "dragCursor");

    private final BooleanProperty animated = new SimpleBooleanProperty(this, "animated", false);

    private final ObjectProperty<Duration> zoomDuration = new SimpleObjectProperty<Duration>(this, "zoomDuration",
            DEFAULT_ZOOM_DURATION) {
        @Override
        protected void invalidated() {
            Objects.requireNonNull(get(), "The " + getName() + " must not be null");
        }
    };

    private final BooleanProperty updateTickUnit = new SimpleBooleanProperty(this, "updateTickUnit", true);

    private final BooleanProperty sliderVisible = new SimpleBooleanProperty(this, "sliderVisible", true);

    private final EventHandler<MouseEvent> zoomInStartHandler = event -> {
        if (getZoomInMouseFilter() == null || getZoomInMouseFilter().test(event)) {
            zoomInStarted(event);
            event.consume();
        }
    };

    private final EventHandler<MouseEvent> zoomInDragHandler = event -> {
        if (zoomOngoing()) {
            zoomInDragged(event);
            event.consume();
        }
    };

    private final EventHandler<MouseEvent> zoomInEndHandler = event -> {
        if (zoomOngoing()) {
            zoomInEnded();
            event.consume();
        }
    };

    private final EventHandler<ScrollEvent> zoomScrollHandler = event -> {
        if (getZoomScrollFilter() == null || getZoomScrollFilter().test(event)) {
            final AxisMode mode = getAxisMode();
            if (zoomStacks.isEmpty()) {
                makeSnapshotOfView();
            }

            for (final Axis axis : getChart().getAxes()) {
                if (axis.getSide() == null || !(axis.getSide().isHorizontal() ? mode.allowsX() : mode.allowsY())) {
                    continue;
                }

                Zoomer.zoomOnAxis(axis, event);
            }

            event.consume();
        }

    };

    private final EventHandler<MouseEvent> zoomOutHandler = event -> {
        if (getZoomOutMouseFilter() == null || getZoomOutMouseFilter().test(event)) {
            final boolean zoomOutPerformed = zoomOut();
            if (zoomOutPerformed) {
                event.consume();
            }
        }
    };

    private final EventHandler<MouseEvent> zoomOriginHandler = event -> {
        if (getZoomOriginMouseFilter() == null || getZoomOriginMouseFilter().test(event)) {
            final boolean zoomOutPerformed = zoomOrigin();
            if (zoomOutPerformed) {
                event.consume();
            }
        }
    };

    /**
     * Creates a new instance of Zoomer with animation disabled and with
     * {@link #axisModeProperty() zoomMode} initialized to {@link AxisMode#XY}.
     */
    public Zoomer() {
        this(AxisMode.XY);
    }

    /**
     * Creates a new instance of Zoomer with animation disabled.
     *
     * @param zoomMode
     *            initial value of {@link #axisModeProperty() zoomMode} property
     */
    public Zoomer(final AxisMode zoomMode) {
        this(zoomMode, false);
    }

    /**
     * Creates a new instance of Zoomer.
     *
     * @param zoomMode
     *            initial value of {@link #axisModeProperty() axisMode} property
     * @param animated
     *            initial value of {@link #animatedProperty() animated} property
     */
    public Zoomer(final AxisMode zoomMode, final boolean animated) {
        super();
        setAxisMode(zoomMode);
        setAnimated(animated);
        setDragCursor(Cursor.CROSSHAIR);

        zoomRectangle.setManaged(false);
        zoomRectangle.getStyleClass().add(STYLE_CLASS_ZOOM_RECT);
        getChartChildren().add(zoomRectangle);
        registerMouseHandlers();

        chartProperty().addListener((change, o, n) -> {
            if (o != null) {
                o.getToolBar().getChildren().remove(zoomButtons);
                o.getPlotArea().setBottom(null);
                xRangeSlider.prefWidthProperty().unbind();
            }
            if (n != null) {
                if (isAddButtonsToToolBar()) {
                    n.getToolBar().getChildren().add(zoomButtons);
                }
                /* always create the slider, even if not visible at first */
                final ZoomRangeSlider slider = new ZoomRangeSlider(n);
                if (isSliderVisible()) {
                    n.getPlotArea().setBottom(slider);
                    xRangeSlider.prefWidthProperty().bind(n.getCanvasForeground().widthProperty());
                }

            }
        });
    }

    /**
     * Creates a new instance of Zoomer with {@link #axisModeProperty()
     * zoomMode} initialized to {@link AxisMode#XY}.
     *
     * @param animated
     *            initial value of {@link #animatedProperty() animated} property
     */
    public Zoomer(final boolean animated) {
        this(AxisMode.XY, animated);
    }

    /**
     * @param axis the axis to be modified
     * @return {@code true} if axis is zoomable, {@code false} otherwise
     */
    public static boolean isOmitZoom(final Axis axis) {
        return (axis instanceof Node) && ((Node) axis).getProperties().get(ZOOMER_OMIT_AXIS) != null;
    }

    /**
     * @param axis the axis to be modified
     * @param state true: axis is not taken into account when zooming
     */
    public static void setOmitZoom(final Axis axis, final boolean state) {
        if (!(axis instanceof Node)) {
            return;
        }
        if (state) {
            ((Node) axis).getProperties().put(ZOOMER_OMIT_AXIS, true);
        } else {
            ((Node) axis).getProperties().remove(ZOOMER_OMIT_AXIS);
        }
    }
    /**
     * limits the mouse event position to the min/max range of the canavs (N.B.
     * event can occur to be negative/larger/outside than the canvas) This is to
     * avoid zooming outside the visible canvas range
     *
     * @param event
     *            the mouse event
     * @param plotBounds
     *            of the canvas
     * @return the clipped mouse location
     */
    private static Point2D limitToPlotArea(final MouseEvent event, final Bounds plotBounds) {
        final double limitedX = Math.max(Math.min(event.getX() - plotBounds.getMinX(), plotBounds.getMaxX()),
                plotBounds.getMinX());
        final double limitedY = Math.max(Math.min(event.getY() - plotBounds.getMinY(), plotBounds.getMaxY()),
                plotBounds.getMinY());
        return new Point2D(limitedX, limitedY);
    }

    private static void zoomOnAxis(final Axis axis, final ScrollEvent event) {
        if (axis.lowerBoundProperty().isBound() || axis.upperBoundProperty().isBound()) {
            return;
        }
        final boolean isZoomIn = event.getDeltaY() > 0;
        final boolean isHorizontal = axis.getSide().isHorizontal();

        final double mousePos = isHorizontal ? event.getX() : event.getY();
        final double posOnAxis = axis.getValueForDisplay(mousePos);
        final double max = axis.getUpperBound();
        final double min = axis.getLowerBound();
        Math.abs(max - min);
        final double scaling = isZoomIn ? 0.9 : 1 / 0.9;
        final double diffHalf1 = scaling * Math.abs(posOnAxis - min);
        final double diffHalf2 = scaling * Math.abs(max - posOnAxis);

        axis.setLowerBound(posOnAxis - diffHalf1);
        axis.setUpperBound(posOnAxis + diffHalf2);

        axis.forceRedraw();
    }

    /**
     * When {@code true} zooming will be animated. By default it's
     * {@code false}.
     *
     * @return the animated property
     * @see #zoomDurationProperty()
     */
    public final BooleanProperty animatedProperty() {
        return animated;
    }

    /**
     * The mode defining axis along which the zoom can be performed. By default
     * initialised to {@link AxisMode#XY}.
     *
     * @return the axis mode property
     */
    public final ObjectProperty<AxisMode> axisModeProperty() {
        return axisMode;
    }

    /**
     * Clears the stack of zoom windows saved during zoom-in operations.
     */
    public void clear() {
        zoomStacks.clear();
    }

    /**
     * Clears the stack of zoom states saved during zoom-in operations for a specific given axis.
     * 
     * @param axis axis zoom history that shall be removed
     */
    public void clear(final Axis axis) {
        for (Map<Axis, ZoomState> stackStage : zoomStacks) {
            stackStage.remove(axis);
        }
    }

    /**
     * Mouse cursor to be used during drag operation.
     *
     * @return the mouse cursor property
     */
    public final ObjectProperty<Cursor> dragCursorProperty() {
        return dragCursor;
    }

    /**
     * Returns the value of the {@link #axisModeProperty()}.
     *
     * @return current mode
     */
    public final AxisMode getAxisMode() {
        return axisModeProperty().get();
    }

    /**
     * Returns the value of the {@link #dragCursorProperty()}
     *
     * @return the current cursor
     */
    public final Cursor getDragCursor() {
        return dragCursorProperty().get();
    }

    public RangeSlider getRangeSlider() {
        return xRangeSlider;
    }

    /**
     * Returns the value of the {@link #zoomDurationProperty()}.
     *
     * @return the current zoom duration
     */
    public final Duration getZoomDuration() {
        return zoomDurationProperty().get();
    }

    /**
     * Returns zoom-in mouse event filter.
     *
     * @return zoom-in mouse event filter
     * @see #setZoomInMouseFilter(Predicate)
     */
    public Predicate<MouseEvent> getZoomInMouseFilter() {
        return zoomInMouseFilter;
    }

    public HBox getZoomInteractorBar() {
        final Separator separator = new Separator();
        separator.setOrientation(Orientation.VERTICAL);
        final HBox buttonBar = new HBox();
        buttonBar.setPadding(new Insets(1, 1, 1, 1));
        final Button zoomOut = new Button(null, new Glyph(FONT_AWESOME, "\uf0b2").size(FONT_SIZE));
        zoomOut.setPadding(new Insets(3, 3, 3, 3));
        zoomOut.setTooltip(new Tooltip("zooms to origin and enables auto-ranging"));
        final Button zoomModeXY = new Button(null, new Glyph(FONT_AWESOME, "\uf047").size(FONT_SIZE));
        zoomModeXY.setPadding(new Insets(3, 3, 3, 3));
        zoomModeXY.setTooltip(new Tooltip("set zoom-mode to X & Y range (N.B. disables auto-ranging)"));
        final Button zoomModeX = new Button(null, new Glyph(FONT_AWESOME, "\uf07e").size(FONT_SIZE));
        zoomModeX.setPadding(new Insets(3, 3, 3, 3));
        zoomModeX.setTooltip(new Tooltip("set zoom-mode to X range (N.B. disables auto-ranging)"));
        final Button zoomModeY = new Button(null, new Glyph(FONT_AWESOME, "\uf07d").size(FONT_SIZE));
        zoomModeY.setPadding(new Insets(3, 3, 3, 3));
        zoomModeY.setTooltip(new Tooltip("set zoom-mode to Y range (N.B. disables auto-ranging)"));

        zoomOut.setOnAction(evt -> {
            zoomOrigin();
            for (final Axis axis : getChart().getAxes()) {
                axis.setAutoRanging(true);
            }
        });
        zoomModeXY.setOnAction(evt -> setAxisMode(AxisMode.XY));
        zoomModeX.setOnAction(evt -> setAxisMode(AxisMode.X));
        zoomModeY.setOnAction(evt -> setAxisMode(AxisMode.Y));
        buttonBar.getChildren().addAll(separator, zoomOut, zoomModeXY, zoomModeX, zoomModeY);
        return buttonBar;
    }

    /**
     * Returns zoom-origin mouse filter.
     *
     * @return zoom-origin mouse filter
     * @see #setZoomOriginMouseFilter(Predicate)
     */
    public Predicate<MouseEvent> getZoomOriginMouseFilter() {
        return zoomOriginMouseFilter;
    }

    /**
     * Returns zoom-out mouse filter.
     *
     * @return zoom-out mouse filter
     * @see #setZoomOutMouseFilter(Predicate)
     */
    public Predicate<MouseEvent> getZoomOutMouseFilter() {
        return zoomOutMouseFilter;
    }

    /**
     * Returns zoom-scroll filter.
     * 
     * @return predicate of filter
     */
    public Predicate<ScrollEvent> getZoomScrollFilter() {
        return zoomScrollFilter;
    }

    /**
     * Returns the value of the {@link #animatedProperty()}.
     *
     * @return {@code true} if zoom is animated, {@code false} otherwise
     * @see #getZoomDuration()
     */
    public final boolean isAnimated() {
        return animatedProperty().get();
    }

    /**
     * Returns the value of the {@link #sliderVisibleProperty()}.
     *
     * @return {@code true} if horizontal range slider is shown
     */
    public final boolean isSliderVisible() {
        return sliderVisibleProperty().get();
    }

    /**
     * Returns the value of the {@link #animatedProperty()}.
     *
     * @return {@code true} if zoom is animated, {@code false} otherwise
     * @see #getZoomDuration()
     */
    public final boolean isUpdateTickUnit() {
        return updateTickUnitProperty().get();
    }

    /**
     * @return list of axes that shall be ignored when performing zoom-in or outs
     */
    public final ObservableList<Axis> omitAxisZoomList() {
        return omitAxisZoom;
    }

    /**
     * Sets the value of the {@link #animatedProperty()}.
     *
     * @param value
     *            if {@code true} zoom will be animated
     * @see #setZoomDuration(Duration)
     */
    public final void setAnimated(final boolean value) {
        animatedProperty().set(value);
    }

    /**
     * Sets the value of the {@link #axisModeProperty()}.
     *
     * @param mode
     *            the mode to be used
     */
    public final void setAxisMode(final AxisMode mode) {
        axisModeProperty().set(mode);
    }

    /**
     * Sets value of the {@link #dragCursorProperty()}.
     *
     * @param cursor
     *            the cursor to be used by the plugin
     */
    public final void setDragCursor(final Cursor cursor) {
        dragCursorProperty().set(cursor);
    }

    /**
     * Sets the value of the {@link #sliderVisibleProperty()}.
     *
     * @param state
     *            if {@code true} the horizontal range slider is shown
     */
    public final void setSliderVisible(final boolean state) {
        sliderVisibleProperty().set(state);
    }

    /**
     * Sets the value of the {@link #animatedProperty()}.
     *
     * @param value
     *            if {@code true} zoom will be animated
     * @see #setZoomDuration(Duration)
     */
    public final void setUpdateTickUnit(final boolean value) {
        updateTickUnitProperty().set(value);
    }

    /**
     * Sets the value of the {@link #zoomDurationProperty()}.
     *
     * @param duration
     *            duration of the zoom
     */
    public final void setZoomDuration(final Duration duration) {
        zoomDurationProperty().set(duration);
    }

    /**
     * Sets filter on {@link MouseEvent#DRAG_DETECTED DRAG_DETECTED} events that
     * should start zoom-in operation.
     *
     * @param zoomInMouseFilter
     *            the filter to accept zoom-in mouse event. If {@code null} then
     *            any DRAG_DETECTED event will start zoom-in operation. By
     *            default it's set to {@link #defaultZoomInMouseFilter}.
     * @see #getZoomInMouseFilter()
     */
    public void setZoomInMouseFilter(final Predicate<MouseEvent> zoomInMouseFilter) {
        this.zoomInMouseFilter = zoomInMouseFilter;
    }

    /**
     * Sets filter on {@link MouseEvent#MOUSE_CLICKED MOUSE_CLICKED} events that
     * should trigger zoom-origin operation.
     *
     * @param zoomOriginMouseFilter
     *            the filter to accept zoom-origin mouse event. If {@code null}
     *            then any MOUSE_CLICKED event will start zoom-origin operation.
     *            By default it's set to
     *            {@link #defaultZoomOriginFilter}.
     * @see #getZoomOriginMouseFilter()
     */
    public void setZoomOriginMouseFilter(final Predicate<MouseEvent> zoomOriginMouseFilter) {
        this.zoomOriginMouseFilter = zoomOriginMouseFilter;
    }

    /**
     * Sets filter on {@link MouseEvent#MOUSE_CLICKED MOUSE_CLICKED} events that
     * should trigger zoom-out operation.
     *
     * @param zoomOutMouseFilter
     *            the filter to accept zoom-out mouse event. If {@code null}
     *            then any MOUSE_CLICKED event will start zoom-out operation. By
     *            default it's set to {@link #defaultZoomOutMouseFilter}.
     * @see #getZoomOutMouseFilter()
     */
    public void setZoomOutMouseFilter(final Predicate<MouseEvent> zoomOutMouseFilter) {
        this.zoomOutMouseFilter = zoomOutMouseFilter;
    }

    /**
     * Sets filter on {@link MouseEvent#MOUSE_CLICKED MOUSE_CLICKED} events that
     * should trigger zoom-origin operation.
     * 
     * @param zoomScrollFilter filter
     */
    public void setZoomScrollFilter(final Predicate<ScrollEvent> zoomScrollFilter) {
        this.zoomScrollFilter = zoomScrollFilter;
    }

    /**
     * When {@code true} an additional horizontal range slider is shown in a
     * HiddeSidesPane at the bottom. By default it's {@code true}.
     *
     * @return the sliderVisible property
     * @see #getRangeSlider()
     */
    public final BooleanProperty sliderVisibleProperty() {
        return sliderVisible;
    }

    /**
     * When {@code true} zooming will be animated. By default it's
     * {@code false}.
     *
     * @return the animated property
     * @see #zoomDurationProperty()
     */
    public final BooleanProperty updateTickUnitProperty() {
        return updateTickUnit;
    }

    /**
     * Duration of the animated zoom (in and out). Used only when
     * {@link #animatedProperty()} is set to {@code true}. By default
     * initialised to 500ms.
     *
     * @return the zoom duration property
     */
    public final ObjectProperty<Duration> zoomDurationProperty() {
        return zoomDuration;
    }

    public boolean zoomOrigin() {
        clearZoomStackIfAxisAutoRangingIsEnabled();
        final Map<Axis, ZoomState> zoomWindows = zoomStacks.peekLast();
        if (zoomWindows == null || zoomWindows.isEmpty()) {
            return false;
        }
        clear();
        performZoom(zoomWindows, false);
        if (xRangeSlider != null) {
            xRangeSlider.reset();
        }
        for (Axis axis : getChart().getAxes()) {
            axis.forceRedraw();
        }
        return true;
    }

    /**
     * While performing zoom-in on all charts we disable auto-ranging on axes
     * (depending on the axisMode) so if user has enabled back the auto-ranging
     * - he wants the chart to adapt to the data. Therefore keeping the zoom
     * stack doesn't make sense - performing zoom-out would again disable
     * auto-ranging and put back ranges saved during the previous zoom-in
     * operation. Also if user enables auto-ranging between two zoom-in
     * operations, the saved zoom stack becomes irrelevant.
     */
    private void clearZoomStackIfAxisAutoRangingIsEnabled() {
        Chart chart = getChart();
        if (chart == null) {
            return;
        }

        for (Axis axis : getChart().getAxes()) {
            if (axis.getSide().isHorizontal()) {
                if (getAxisMode().allowsX() && (axis.isAutoRanging() || axis.isAutoGrowRanging())) {
                    clear(axis);
                }
            } else {
                if (getAxisMode().allowsY() && (axis.isAutoRanging() || axis.isAutoGrowRanging())) {
                    clear(axis);
                }
            }
        }
    }

    private Map<Axis, ZoomState> getZoomDataWindows() {
        ConcurrentHashMap<Axis, ZoomState> axisStateMap = new ConcurrentHashMap<>();
        if (getChart() == null) {
            return axisStateMap;
        }
        final double minX = zoomRectangle.getX();
        final double minY = zoomRectangle.getY() + zoomRectangle.getHeight();
        final double maxX = zoomRectangle.getX() + zoomRectangle.getWidth();
        final double maxY = zoomRectangle.getY();

        // pixel coordinates w.r.t. plot area
        final Point2D minPlotCoordinate = getChart().toPlotArea(minX, minY);
        final Point2D maxPlotCoordinate = getChart().toPlotArea(maxX, maxY);
        for (Axis axis : getChart().getAxes()) {
            double dataMin;
            double dataMax;
            if (axis.getSide().isVertical()) {
                dataMin = axis.getValueForDisplay(minPlotCoordinate.getY());
                dataMax = axis.getValueForDisplay(maxPlotCoordinate.getY());
            } else {
                dataMin = axis.getValueForDisplay(minPlotCoordinate.getX());
                dataMax = axis.getValueForDisplay(maxPlotCoordinate.getX());
            }
            axisStateMap.put(axis, new ZoomState(dataMin, dataMax, axis.isAutoRanging(), axis.isAutoGrowRanging()));
        }

        return axisStateMap;
    }

    private void installCursor() {
        final Region chart = getChart();
        originalCursor = chart.getCursor();
        if (getDragCursor() != null) {
            chart.setCursor(getDragCursor());
        }
    }

    private boolean isMouseEventWithinCanvas(final MouseEvent mouseEvent) {
        final Canvas canvas = getChart().getCanvas();
        // listen to only events within the canvas
        final Point2D mouseLoc = new Point2D(mouseEvent.getScreenX(), mouseEvent.getScreenY());
        final Bounds screenBounds = canvas.localToScreen(canvas.getBoundsInLocal());
        return screenBounds.contains(mouseLoc);
    }

    private boolean isMouseEventWithinCanvas(final ScrollEvent mouseEvent) {
        final Canvas canvas = getChart().getCanvas();
        // listen to only events within the canvas
        final Point2D mouseLoc = new Point2D(mouseEvent.getScreenX(), mouseEvent.getScreenY());
        final Bounds screenBounds = canvas.localToScreen(canvas.getBoundsInLocal());
        return screenBounds.contains(mouseLoc);
    }

    /**
     * @param axis the axis to be modified
     * @return {@code true} if axis is zoomable, {@code false} otherwise
     */
    private boolean isOmitZoomInternal(final Axis axis) {
        final boolean propertyState = Zoomer.isOmitZoom(axis);

        return propertyState || omitAxisZoomList().contains(axis);
    }

    /**
     * take a snapshot of present view (needed for scroll zoom interactor
     */
    private void makeSnapshotOfView() {
        final Bounds bounds = getChart().getBoundsInLocal();
        final double minX = bounds.getMinX();
        final double minY = bounds.getMinY();
        final double maxX = bounds.getMaxX();
        final double maxY = bounds.getMaxY();

        zoomRectangle.setX(bounds.getMinX());
        zoomRectangle.setY(bounds.getMinY());
        zoomRectangle.setWidth(maxX - minX);
        zoomRectangle.setHeight(maxY - minY);

        pushCurrentZoomWindows();
        performZoom(getZoomDataWindows(), true);
        zoomRectangle.setVisible(false);
    }

    private void performZoom(Entry<Axis, ZoomState> zoomStateEntry, final boolean isZoomIn) {
        Axis axis = zoomStateEntry.getKey();
        ZoomState zoomState = zoomStateEntry.getValue();

        if (isZoomIn && ((axis.getSide().isHorizontal() && getAxisMode().allowsX())
                || (axis.getSide().isVertical() && getAxisMode().allowsY()))) {
            // perform only zoom-in if axis is horizontal (or vertical) and corresponding horizontal (or vertical) zooming is allowed
            axis.setAutoRanging(false);
        }

        if (isAnimated()) {
            if (!Axes.hasBoundedRange(axis)) {
                final Timeline xZoomAnimation = new Timeline();
                xZoomAnimation.getKeyFrames().setAll(
                        new KeyFrame(Duration.ZERO, new KeyValue(axis.lowerBoundProperty(), axis.getLowerBound()),
                                new KeyValue(axis.upperBoundProperty(), axis.getUpperBound())),
                        new KeyFrame(getZoomDuration(), new KeyValue(axis.lowerBoundProperty(), zoomState.zoomRangeMin),
                                new KeyValue(axis.upperBoundProperty(), zoomState.zoomRangeMax)));
                xZoomAnimation.play();
            }
        } else {
            if (!Axes.hasBoundedRange(axis)) {
                // only update if this axis is not bound to another (e.g. auto-range) managed axis)
                axis.setLowerBound(zoomState.zoomRangeMin);
                axis.setUpperBound(zoomState.zoomRangeMax);
            }
        }

        if (!isZoomIn) {
            axis.setAutoRanging(zoomState.wasAutoRanging);
            axis.setAutoGrowRanging(zoomState.wasAutoGrowRanging);
        }
    }

    private void performZoom(Map<Axis, ZoomState> zoomWindows, final boolean isZoomIn) {
        for (final Entry<Axis, ZoomState> entry : zoomWindows.entrySet()) {
            if (!isOmitZoomInternal(entry.getKey())) {
                performZoom(entry, isZoomIn);
            }
        }

        for (Axis a : getChart().getAxes()) {
            a.forceRedraw();
        }
    }

    private void performZoomIn() {
        clearZoomStackIfAxisAutoRangingIsEnabled();
        pushCurrentZoomWindows();
        performZoom(getZoomDataWindows(), true);
    }

    private void pushCurrentZoomWindows() {
        if (getChart() == null) {
            return;
        }
        ConcurrentHashMap<Axis, ZoomState> axisStateMap = new ConcurrentHashMap<>();
        for (Axis axis : getChart().getAxes()) {
            axisStateMap.put(axis, new ZoomState(axis.getLowerBound(), axis.getUpperBound(), axis.isAutoRanging(),
                    axis.isAutoGrowRanging()));
        }
        zoomStacks.addFirst(axisStateMap);
    }

    private void registerMouseHandlers() {
        registerInputEventHandler(MouseEvent.MOUSE_PRESSED, zoomInStartHandler);
        registerInputEventHandler(MouseEvent.MOUSE_DRAGGED, zoomInDragHandler);
        registerInputEventHandler(MouseEvent.MOUSE_RELEASED, zoomInEndHandler);
        registerInputEventHandler(MouseEvent.MOUSE_CLICKED, zoomOutHandler);
        registerInputEventHandler(MouseEvent.MOUSE_CLICKED, zoomOriginHandler);
        registerInputEventHandler(ScrollEvent.SCROLL, zoomScrollHandler);
    }

    private void uninstallCursor() {
        getChart().setCursor(originalCursor);
    }

    private void zoomInDragged(final MouseEvent event) {
        final Bounds plotAreaBounds = getChart().getPlotArea().getBoundsInLocal();
        zoomEndPoint = limitToPlotArea(event, plotAreaBounds);

        double zoomRectX = plotAreaBounds.getMinX();
        double zoomRectY = plotAreaBounds.getMinY();
        double zoomRectWidth = plotAreaBounds.getWidth();
        double zoomRectHeight = plotAreaBounds.getHeight();

        if (getAxisMode().allowsX()) {
            zoomRectX = Math.min(zoomStartPoint.getX(), zoomEndPoint.getX());
            zoomRectWidth = Math.abs(zoomEndPoint.getX() - zoomStartPoint.getX());
        }
        if (getAxisMode().allowsY()) {
            zoomRectY = Math.min(zoomStartPoint.getY(), zoomEndPoint.getY());
            zoomRectHeight = Math.abs(zoomEndPoint.getY() - zoomStartPoint.getY());
        }
        zoomRectangle.setX(zoomRectX);
        zoomRectangle.setY(zoomRectY);
        zoomRectangle.setWidth(zoomRectWidth);
        zoomRectangle.setHeight(zoomRectHeight);
    }

    private void zoomInEnded() {
        zoomRectangle.setVisible(false);
        if (zoomRectangle.getWidth() > ZOOM_RECT_MIN_SIZE && zoomRectangle.getHeight() > ZOOM_RECT_MIN_SIZE) {
            performZoomIn();
        }
        zoomStartPoint = zoomEndPoint = null;
        uninstallCursor();
    }

    private void zoomInStarted(final MouseEvent event) {
        zoomStartPoint = new Point2D(event.getX(), event.getY());

        zoomRectangle.setX(zoomStartPoint.getX());
        zoomRectangle.setY(zoomStartPoint.getY());
        zoomRectangle.setWidth(0);
        zoomRectangle.setHeight(0);
        zoomRectangle.setVisible(true);
        installCursor();
    }

    private boolean zoomOngoing() {
        return zoomStartPoint != null;
    }

    private boolean zoomOut() {
        clearZoomStackIfAxisAutoRangingIsEnabled();
        final Map<Axis, ZoomState> zoomWindows = zoomStacks.pollFirst();
        if (zoomWindows == null || zoomWindows.isEmpty()) {
            return zoomOrigin();
        }
        performZoom(zoomWindows, false);
        return true;
    }

    private class ZoomRangeSlider extends RangeSlider {
        private final BooleanProperty invertedSlide = new SimpleBooleanProperty(this, "invertedSlide", false);
        private boolean isUpdating = false;
        private final ChangeListener<Boolean> sliderResetHandler = (ch, o, n) -> {
            if (getChart() == null) {
                return;
            }
            final Axis axis = getChart().getFirstAxis(Orientation.HORIZONTAL);
            if (n) {
                final double minBound = axis.getLowerBound();
                final double maxBound = axis.getUpperBound();
                setMin(minBound);
                setMax(maxBound);
            }
        };

        private final ChangeListener<Number> rangeChangeListener = (ch, o, n) -> {
            if (isUpdating) {
                return;
            }
            isUpdating = true;
            final Axis xAxis = getChart().getFirstAxis(Orientation.HORIZONTAL);
            xAxis.getUpperBound();
            xAxis.getLowerBound();
            // add a little bit of margin to allow zoom outside the dataset
            final double minBound = Math.min(xAxis.getLowerBound(), getMin());
            final double maxBound = Math.max(xAxis.getUpperBound(), getMax());
            if (xRangeSliderInit) {
                setMin(minBound);
                setMax(maxBound);
            }
            isUpdating = false;
        };

        private final ChangeListener<Number> sliderValueChanged = (ch, o, n) -> {
            if (!isSliderVisible() || n == null || isUpdating) {
                return;
            }
            isUpdating = true;
            final Axis xAxis = getChart().getFirstAxis(Orientation.HORIZONTAL);
            if (xAxis.isAutoRanging() || xAxis.isAutoGrowRanging()) {
                setMin(xAxis.getLowerBound());
                setMax(xAxis.getUpperBound());
                isUpdating = false;
                return;
            }
            isUpdating = false;
        };

        private final EventHandler<? super MouseEvent> mouseEventHandler = (final MouseEvent event) -> {
            // disable auto ranging only when the slider interactor was used
            // by mouse/user
            // this is a work-around since the ChangeListener interface does
            // not contain
            // a event source object
            if (zoomStacks.isEmpty()) {
                makeSnapshotOfView();
            }
            final Axis xAxis = getChart().getFirstAxis(Orientation.HORIZONTAL);
            xAxis.setAutoRanging(false);
            xAxis.setAutoGrowRanging(false);
            xAxis.setLowerBound(getLowValue());
            xAxis.setUpperBound(getHighValue());
        };

        public ZoomRangeSlider(final Chart chart) {
            super();
            final Axis xAxis = chart.getFirstAxis(Orientation.HORIZONTAL);
            xRangeSlider = this;
            setPrefWidth(-1);
            setMaxWidth(Double.MAX_VALUE);

            xAxis.invertAxisProperty().bindBidirectional(invertedSlide);
            invertedSlide.addListener((ch, o, n) -> ZoomRangeSlider.this.setRotate(n ? 180 : 0));

            xAxis.autoRangingProperty().addListener(sliderResetHandler);
            xAxis.autoGrowRangingProperty().addListener(sliderResetHandler);

            xAxis.lowerBoundProperty().addListener(rangeChangeListener);
            xAxis.upperBoundProperty().addListener(rangeChangeListener);

            // rstein: needed in case of autoranging/sliding xAxis (see
            // RollingBuffer for example)            
            lowValueProperty().addListener(sliderValueChanged);
            highValueProperty().addListener(sliderValueChanged);

            setOnMouseReleased(mouseEventHandler);

            lowValueProperty().bindBidirectional(xAxis.lowerBoundProperty());
            highValueProperty().bindBidirectional(xAxis.upperBoundProperty());

            sliderVisibleProperty().addListener((ch, o, n) -> {
                if (getChart() == null || n.equals(o) || isUpdating) {
                    return;
                }
                isUpdating = true;
                if (n) {
                    getChart().getPlotArea().setBottom(xRangeSlider);
                    prefWidthProperty().bind(getChart().getCanvasForeground().widthProperty());
                } else {
                    getChart().getPlotArea().setBottom(null);
                    prefWidthProperty().unbind();
                }
                isUpdating = false;
            });

            addButtonsToToolBarProperty().addListener((ch, o, n) -> {
                final Chart chartLocal = getChart();
                if (chartLocal == null || n.equals(o)) {
                    return;
                }
                if (n) {
                    chartLocal.getToolBar().getChildren().add(zoomButtons);
                } else {
                    chartLocal.getToolBar().getChildren().remove(zoomButtons);
                }
            });

            xRangeSliderInit = true;
        }

        public void reset() {
            sliderResetHandler.changed(null, false, true);
        }

    } // ZoomRangeSlider

    /**
     * small class used to remember whether the autorange axis was on/off to be
     * able to restore the original state on unzooming
     */
    private class ZoomState {
        protected double zoomRangeMin;
        protected double zoomRangeMax;
        protected boolean wasAutoRanging;
        protected boolean wasAutoGrowRanging;

        ZoomState(final double zoomRangeMin, final double zoomRangeMax, final boolean isAutoRanging,
                final boolean isAutoGrowRanging) {
            this.zoomRangeMin = zoomRangeMin;
            this.zoomRangeMax = zoomRangeMax;
            this.wasAutoRanging = isAutoRanging;
            this.wasAutoGrowRanging = isAutoGrowRanging;
        }
    }
}