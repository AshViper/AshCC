package com.ashcc.gametest;

import com.ashcc.AshCC;
import com.ashcc.monitor.Canvas;
import com.ashcc.monitor.HdMonitorBlock;
import com.ashcc.monitor.HdMonitorBlockEntity;
import com.ashcc.monitor.HdMonitorPeripheral;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.Map;

@GameTestHolder(AshCC.MOD_ID)
@PrefixGameTestTemplate(false)
public class MonitorTests {

    static BlockState monitor(Direction facing, Direction rotation) {
        return AshCC.HD_MONITOR.get().defaultBlockState().setValue(HdMonitorBlock.FACING, facing).setValue(HdMonitorBlock.ROTATION, rotation);
    }

    static HdMonitorBlockEntity be(GameTestHelper helper, BlockPos pos) {
        return (HdMonitorBlockEntity) helper.getBlockEntity(pos);
    }

    /** A 3x2 wall joins into one monitor; breaking a block splits it again. Screen faces south: right = east (+x). */
    @GameTest(template = RadarTests.EMPTY)
    public static void wallMonitorsJoin(GameTestHelper helper) throws LuaException {
        for (int x = 1; x <= 3; x++) for (int y = 1; y <= 2; y++) helper.setBlock(new BlockPos(x, y, 2), monitor(Direction.SOUTH, Direction.NORTH));
        HdMonitorBlockEntity origin = be(helper, new BlockPos(1, 2, 2));
        helper.assertTrue(origin.isOrigin() && origin.width() == 3 && origin.height() == 2, "not 3x2 from the top-left: " + origin.width() + "x" + origin.height());
        HdMonitorBlockEntity corner = be(helper, new BlockPos(3, 1, 2));
        helper.assertTrue(corner.xIndex() == 2 && corner.yIndex() == 1, "bottom-right is at " + corner.xIndex() + "," + corner.yIndex());
        HdMonitorPeripheral p = corner.peripheral();
        helper.assertTrue(p != null && Arrays.equals(p.getResolution(), new Object[]{384, 256}), "resolution");
        helper.assertTrue(p.equals(origin.peripheral()), "blocks of one monitor are different peripherals");

        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        // Greedy from the top-left: left column 1x2, right column 1x2, the bottom-middle block alone
        HdMonitorBlockEntity left = be(helper, new BlockPos(1, 2, 2)), middle = be(helper, new BlockPos(2, 1, 2));
        helper.assertTrue(left.width() == 1 && left.height() == 2 && middle.isOrigin() && middle.width() == 1 && middle.height() == 1,
            "split is not two columns + one block");
        helper.assertTrue(p.getResolution()[0].equals(384) && !p.equals(left.peripheral()), "old peripheral still matches the new monitor");
        helper.succeed();
    }

    /** Floor monitors join on the horizontal plane; their top points where the player looked. */
    @GameTest(template = RadarTests.EMPTY)
    public static void floorMonitorsJoin(GameTestHelper helper) {
        for (int x = 1; x <= 2; x++) for (int z = 1; z <= 3; z++) helper.setBlock(new BlockPos(x, 1, z), monitor(Direction.UP, Direction.NORTH));
        // Top = north (-z), right = east (+x): the origin is the north-west block
        HdMonitorBlockEntity origin = be(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(origin.isOrigin() && origin.width() == 2 && origin.height() == 3, "floor monitor " + origin.width() + "x" + origin.height());
        helper.succeed();
    }

    /** Shapes, text, blending and the term emulation write the expected pixels. */
    @GameTest(template = RadarTests.EMPTY)
    public static void drawing(GameTestHelper helper) throws LuaException {
        helper.setBlock(new BlockPos(2, 2, 2), monitor(Direction.SOUTH, Direction.NORTH));
        HdMonitorPeripheral p = be(helper, new BlockPos(2, 2, 2)).peripheral();
        helper.assertTrue(p != null, "no peripheral");
        p.fill(new ObjectArguments(0x000000));
        p.fillRect(new ObjectArguments(10, 10, 20, 20, 0xFF0000));
        helper.assertTrue(p.getPixel(15, 15) == 0xFF0000 && p.getPixel(30, 30) == 0, "fillRect");
        // 50% white over red
        p.fillRect(new ObjectArguments(10, 10, 5, 5, "#ffffff80"));
        double mixed = p.getPixel(11, 11);
        int g = ((int) mixed >> 8) & 255;
        helper.assertTrue(Math.abs(g - 128) <= 2, "blend green " + g);
        // Anti-aliased circle: centre solid, edge partially covered
        p.fillCircle(new ObjectArguments(64, 64, 10, "#00ff00"));
        helper.assertTrue(p.getPixel(64, 64) == 0x00FF00, "circle centre");
        int edgeG = ((int) p.getPixel(74, 64) >> 8) & 255;
        helper.assertTrue(edgeG > 20 && edgeG < 235, "circle edge not anti-aliased: " + edgeG);
        // Text: "A" at 2x, Japanese glyph present
        // A Lua string holds UTF-8 bytes; CC hands them to Java one char per byte (Latin-1)
        String utf8 = new String("Aあ".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
        Object[] size = p.drawText(new ObjectArguments(0, 90, utf8, 0xFFFFFF, Map.of("scale", 2.0)));
        helper.assertTrue(((Number) size[0]).intValue() == (8 + 16) * 2 && ((Number) size[1]).intValue() == 32, "text size " + size[0]);
        boolean lit = false;
        for (int y = 90; y < 122; y++) for (int x = 16; x < 48; x++) lit |= p.getPixel(x, y) == 0xFFFFFF;
        helper.assertTrue(lit, "Japanese glyph not drawn");
        // Term: scale 0.5 = 8x16 cells -> 16x8 characters on one block
        p.setTextScale(0.5);
        helper.assertTrue(Arrays.equals(p.getSize(), new Object[]{16, 8}), "term size " + Arrays.toString(p.getSize()));
        p.setBackgroundColour(1 << 14); // red
        p.setCursorPos(1, 8);
        p.write(new ObjectArguments(" "));
        helper.assertTrue(p.getPixel(3, 127) == 0xCC4C4C, "term background");
        helper.assertTrue(Arrays.equals(p.getCursorPos(), new Object[]{2, 8}), "cursor");
        p.scroll(1);
        helper.assertTrue(p.getPixel(3, 111) == 0xCC4C4C, "scroll did not move the row up");
        helper.succeed();
    }

    /** With auto-present off nothing shows until present(). */
    @GameTest(template = RadarTests.EMPTY)
    public static void framePresent(GameTestHelper helper) throws LuaException {
        helper.setBlock(new BlockPos(2, 2, 2), monitor(Direction.SOUTH, Direction.NORTH));
        HdMonitorPeripheral p = be(helper, new BlockPos(2, 2, 2)).peripheral();
        p.setAutoPresent(false);
        p.fill(new ObjectArguments(0x123456));
        helper.assertTrue(p.getPixel(0, 0) == 0, "drawn before present");
        p.present();
        helper.assertTrue(p.getPixel(0, 0) == 0x123456, "not drawn after present");
        helper.succeed();
    }

    /** A snapshot rebuilds an identical canvas, and the same operations give the same pixels. */
    @GameTest(template = RadarTests.EMPTY)
    public static void snapshotRoundTrip(GameTestHelper helper) {
        ByteBuf ops = Unpooled.buffer();
        Canvas.line(ops, 3.3, 4.1, 120.7, 90.2, 3.5, 0xC0FF8000);
        Canvas.ellipse(ops, 60, 60, 40, 25, 2, 0xFF00FFFF);
        Canvas.polygon(ops, new double[]{10, 10, 100, 20, 50, 110}, 0, 0x8000FF00);
        Canvas.text(ops, 5, 5, 1, 0xFFFFFFFF, 0x80000000, "Hi モニター");
        Canvas a = new Canvas(128, 128), b = new Canvas(128, 128);
        a.apply(ops.duplicate());
        b.apply(ops.duplicate());
        helper.assertTrue(Arrays.equals(a.pixels, b.pixels), "same operations, different pixels");
        Canvas c = new Canvas(128, 128);
        a.snapshot(c::apply, 4096);
        helper.assertTrue(Arrays.equals(a.pixels, c.pixels), "snapshot differs");
        helper.succeed();
    }
}
