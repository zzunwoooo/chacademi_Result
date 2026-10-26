package kr.chacademy.story.dialogue;
import java.util.ArrayList;
import java.util.List;
/** Constructor-time progress/events wait until the UI accepted the first frame. */
public final class DialogueOpenSignals {
    private final List<Runnable> buffered = new ArrayList<>();
    private boolean accepted, rejected;
    public void send(Runnable signal) { if(rejected)return; if(accepted)signal.run();else buffered.add(signal); }
    public void accept() { if(rejected||accepted)return; accepted=true;var copy=List.copyOf(buffered);buffered.clear();for(Runnable signal:copy){if(rejected)break;signal.run();} }
    public boolean isRejected(){return rejected;}
    public void reject() { rejected=true;buffered.clear(); }
}
