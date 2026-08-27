package perfectpathc.dev.modules.impl;

import java.lang.reflect.Field;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;

public final class ClickerUtils {
  private static final Field mouseButton = findField(MouseEvent.class, int.class, "button");
  
  private static final Field buttonState = findField(MouseEvent.class, boolean.class, "buttonstate");
  
  public static void sendFakeRight() {
    sendFakeMouseEvent(1);
  }
  
  public static void sendFakeLeft() {
    sendFakeMouseEvent(0);
  }
  
  private static void sendFakeMouseEvent(int button) {
    if (mouseButton == null || buttonState == null)
      return; 
    try {
      MouseEvent event = new MouseEvent();
      mouseButton.setInt(event, button);
      buttonState.setBoolean(event, true);
      MinecraftForge.EVENT_BUS.post((Event)event);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("No se pudo crear el evento de ratón", exception);
    } 
  }
  
  private static Field findField(Class<?> type, Class<?> fieldType, String preferredName) {
    for (Field field : type.getDeclaredFields()) {
      if (field.getType() == fieldType && field.getName().equalsIgnoreCase(preferredName)) {
        field.setAccessible(true);
        return field;
      } 
    } 
    for (Field field : type.getDeclaredFields()) {
      if (field.getType() == fieldType) {
        field.setAccessible(true);
        return field;
      } 
    } 
    return null;
  }
}
