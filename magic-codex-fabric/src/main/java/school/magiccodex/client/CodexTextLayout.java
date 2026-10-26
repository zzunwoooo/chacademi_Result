package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Word-aware, code-point-safe wrapping shared by previews and full-text hover panels. */
final class CodexTextLayout {
    private CodexTextLayout() {}
    static List<String> wrap(String text, double width, ToDoubleFunction<String> measure) {
        var result=new ArrayList<String>();
        for(String paragraph:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1)) {
            if(paragraph.isEmpty()){result.add("");continue;}
            String remaining=paragraph;
            while(!remaining.isEmpty()) {
                int end=0,low=1,high=remaining.codePointCount(0,remaining.length());
                while(low<=high) {
                    int mid=(low+high)>>>1,next=remaining.offsetByCodePoints(0,mid);
                    if(measure.applyAsDouble(remaining.substring(0,next))<=width){end=next;low=mid+1;}
                    else high=mid-1;
                }
                // Even an overwide single glyph must advance, without splitting surrogate pairs.
                if(end==0)end=remaining.offsetByCodePoints(0,1);
                if(end<remaining.length()) {
                    int space=remaining.lastIndexOf(' ',end);
                    if(space>0)end=space;
                }
                result.add(remaining.substring(0,end).stripTrailing());
                remaining=remaining.substring(end).stripLeading();
            }
        }
        return List.copyOf(result);
    }
    static List<String> preview(List<String> lines,int limit,double width,ToDoubleFunction<String> measure) {
        if(lines.size()<=limit)return lines;
        var visible=new ArrayList<>(lines.subList(0,limit));
        String last=visible.getLast();
        while(!last.isEmpty() && measure.applyAsDouble(last+"…")>width)
            last=last.substring(0,last.offsetByCodePoints(last.length(),-1));
        visible.set(limit-1,last+"…");
        return List.copyOf(visible);
    }
}
