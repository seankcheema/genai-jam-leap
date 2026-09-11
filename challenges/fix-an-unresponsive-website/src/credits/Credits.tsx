import {memo, useEffect, useState} from "react";

// ---------------------------------------------------------------------------
// ROOT CAUSE OF THE HANG (read this before the rest of the file):
//
// `credits` and `DisplayCredit` used to be declared *inside* the Credits()
// component function body. That was harmless in the original code because
// Credits only ever rendered once per mount. It stops being harmless once we
// need Credits to re-render multiple times (which the fix below does, on
// purpose, to spread the work out) because:
//   1. `credits` would be a brand-new array of brand-new {name, role} object
//      literals on every render, so no child's `credit` prop would ever be
//      referentially equal to what it was on the previous render.
//   2. `DisplayCredit` would be a brand-new function *type* on every render,
//      so React could not recognise <DisplayCredit key={i} .../> as "the
//      same component instance" across renders - it would unmount and
//      remount it instead of reconciling it.
// Both of those would defeat the fix below (they'd make React re-run the
// 100ms busy-wait for every *previously shown* credit on every subsequent
// render, turning an O(n) * 100ms cost into an O(n^2) one). So both are now
// hoisted to module scope, and DisplayCredit is wrapped in React.memo - see
// the comment directly above DisplayCredit for why that specifically matters.
// ---------------------------------------------------------------------------

const credits = [
    {name: "John", role: "developer"},
    {name: "Jane", role: "developer"},
    {name: "Jack", role: "developer"},
    {name: "Jill", role: "developer"},
    {name: "James", role: "developer"},
    {name: "Jenny", role: "developer"},
    {name: "Jade", role: "developer"},
    {name: "Jasmine", role: "developer"},
    {name: "Jasper", role: "developer"},
    {name: "Jared", role: "developer"},
    {name: "Jocelyn", role: "developer"},
    {name: "Jude", role: "developer"},
    {name: "Jules", role: "developer"},
    {name: "Julian", role: "developer"},
    {name: "Julia", role: "developer"}];

// Wrapped in React.memo (and hoisted to module scope, see comment above) so
// that once a given credit has been rendered, later re-renders of the parent
// (triggered by the incremental reveal in Credits, below) do NOT call this
// function again for that credit. React.memo does a shallow comparison of
// props, and because `credits` above is now a stable module-level array,
// `props.credit` is the exact same object reference on every render for any
// credit that has already been shown - so memo bails out and skips
// re-invoking the component, meaning the busy-wait for that credit is not
// repeated. This is what keeps the fix's total cost at O(n) * 100ms (same as
// the original) instead of O(n^2) - we are only ever making sure the delay
// runs ONCE per credit, exactly as it did before.
const DisplayCredit = memo((props: {credit: {name: string, role: string}}) => {
    const start = performance.now();
    while(performance.now() -  start  < 100) {
        //do nothing - create a delay to simulate a long operation
        // *** Intentional delay - required by the challenge to remain untouched ***
    }
    return <p>{props.credit.name} - {props.credit.role}</p>;
});

const Credits = () => {

    // -------------------------------------------------------------------
    // THE FIX: reveal credits one at a time, yielding to the browser
    // between each one, instead of rendering all 15 in a single pass.
    //
    // (a) Why the original code hung the menu:
    //     The old Credits() built the *entire* `data` array of all 15
    //     <DisplayCredit> elements up front and returned it in one go.
    //     React render functions execute synchronously on the browser's
    //     main JS thread, and React does not hand control back to the
    //     browser until the whole render finishes. The busy-wait loop is
    //     plain synchronous CPU work (not a Promise/timer/anything
    //     awaitable), so while it runs, the JS thread cannot do anything
    //     else - including pop a queued "click" event off the event queue
    //     and run the Menu/<Link> handler for it. With 15 credits x 100ms
    //     each rendered back-to-back inside one render, that's ~1500ms of
    //     completely unbroken JS execution, during which any click on the
    //     menu just sits queued, making the page look frozen. Note that
    //     React's concurrent APIs (startTransition, useTransition,
    //     useDeferredValue) would NOT have fixed this: those let React
    //     interleave/interrupt work *between separately scheduled render
    //     units*, but they cannot chop up a single synchronous function
    //     call that itself contains 1500ms of blocking work - the old code
    //     only ever created one such unit (one call to Credits() producing
    //     all 15 children in one commit).
    //
    // (b) What this fix does, mechanically:
    //     `visibleCount` starts at 0, so Credits initially renders none of
    //     the 15 credits (fast, non-blocking). The effect below then
    //     kicks off a chain of `setTimeout(fn, 0)` calls: each callback
    //     runs in its own fresh macrotask, increments `visibleCount` by
    //     one via setState, and schedules the next callback. Each
    //     resulting state update triggers an independent React render
    //     that shows exactly one additional credit (and so pays that
    //     credit's 100ms busy-wait). Because each 100ms chunk of work now
    //     runs in its own macrotask rather than all 15 running inside one,
    //     the browser's event loop regains control *between* chunks -
    //     it can dispatch queued input events (e.g. a menu click) and
    //     paint, before the next chunk's setTimeout callback fires.
    //
    // (c) Why this genuinely fixes it despite the 100ms-per-item delay
    //     still running exactly as before:
    //     Line ~54's `while (performance.now() - start < 100)` is
    //     completely unmodified, and the total time for all 15 credits to
    //     finish appearing is still ~1500ms - we have not made the work
    //     itself any faster or skipped any of it. What changed is that
    //     those 1500ms are no longer ONE uninterrupted block; they are now
    //     15 separate ~100ms blocks with a genuine yield point (a
    //     macrotask boundary) between each one. The menu's click handler
    //     therefore never has to wait for more than a single ~100ms chunk
    //     (worst case) before the browser can service it, instead of
    //     waiting for the full ~1500ms - which is the difference between
    //     "hangs for 1.5s" and "stays responsive throughout".
    // -------------------------------------------------------------------
    const [visibleCount, setVisibleCount] = useState(0);

    useEffect(() => {
        let cancelled = false;

        const scheduleNext = (count: number) => {
            if (cancelled || count >= credits.length) {
                return;
            }
            // setTimeout(..., 0) queues a macrotask rather than running
            // synchronously - that macrotask boundary is the actual yield
            // point that lets the browser process pending input (like a
            // menu click) and paint before the next 100ms chunk runs.
            setTimeout(() => {
                if (cancelled) {
                    return;
                }
                const next = count + 1;
                setVisibleCount(next);
                scheduleNext(next);
            }, 0);
        };

        scheduleNext(0);

        // Credits is mounted/unmounted wholesale by AboutPage's
        // `{showCredits && <Credits />}`, so this cleanup fires if the
        // user hides credits (or navigates away) before all 15 have been
        // revealed - it stops queueing further chunks of work.
        return () => {
            cancelled = true;
        };
    }, []);

    const data = [];
    for (let i = 0; i < visibleCount; i++) {
        data.push(<DisplayCredit key={i} credit={credits[i]} />);
    }

    return (<div>This app was written by:
        {data}
    </div>)

}

export default Credits;
