package kr.chacademi.chatlayout;
/** Modifier state is sampled both before rendering and on each pointer movement. */
public final class EditGesturePolicy {
    private EditGesturePolicy(){}
    public static boolean modifierReleased(int key,int action){
        return action==0&&(key==341||key==345||key==340||key==344);
    }
    public static boolean held(boolean control,boolean shift){return control&&shift;}
}
