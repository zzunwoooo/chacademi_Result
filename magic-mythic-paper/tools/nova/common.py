"""Helpers for the 325~387 rows (tools/nova/*.py). See tools/nova_spells.py for the row shape."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from arcana_spells import ph, X, att, sta, imp, lnk  # noqa: F401

def N(typ, bundle, s=''):
    """chanova line for spell `typ` (= spell id) whose VFX pack folder is `bundle` (pack id = bundle in lower case)."""
    return 'chanova{type=' + typ + ';pack=' + bundle.lower() + (';' + s if s else '') + '}'

def ev(system, role, dur, anchor=None, delay=0, stop=None, src=None, dst=None):
    """Phase started by the mechanic's `external:<system>` event (Nova.fx), playing the pack system of the same name.
    role attached: anchor 'target' (the entity passed to fx) or 'caster'; static / impact: at the event point;
    link: src -> dst ('caster' | 'caster-hand' | 'event-point' | 'target')."""
    if role == 'attached': return ph(X(system), system, 'attached', anchor or 'target', dur, delay, stop)
    if role == 'link': return ph(X(system), system, 'link', 'impact', dur, delay, stop, src or 'caster', dst or 'event-point')
    return ph(X(system), system, role, 'impact', dur, delay, stop)

def cast(system, dur, anchor='caster', delay=0, role='attached', stop=None):
    """Phase started with the cast itself (t = 0 + delay ticks). anchor 'caster' (attached) or 'target' (static at the aim point)."""
    return ph('cast', system, role, anchor, dur, delay, stop)
