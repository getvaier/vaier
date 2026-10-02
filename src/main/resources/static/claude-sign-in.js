// Signing in to Claude on a machine — the one copy of it.
//
// A sign-in is a terminal act. It runs the Claude CLI in one OS user's home on one machine, and what it
// leaves behind belongs to that user alone. So the UI lives in the shell window (terminal.html), which IS
// that machine's terminal as that user — not on the Explorer's machine pane, where you read ABOUT a machine
// rather than act on it.
//
// Vaier never holds a Claude credential. It runs the CLI on the machine, shows the operator the
// authorization URL that CLI printed, and carries back the code Anthropic gave them. Nothing of Anthropic's
// ever rests here, and no endpoint in this file is anything but a message to the CLI on the far side.
//
// Two surfaces read this file. The Explorer takes only `words()`: a machine card's Claude mark says where a
// machine stands, and it must say it in the same vocabulary the sign-in does, or one machine reads one thing
// on its card and something else in its shell. The shell window takes `mount()` and gets the whole thing.
(function () {
    'use strict';

    // The operator's words for each state, never the enum's. UNKNOWN is the one that matters: the backend goes
    // to real trouble never to report a false SIGNED_OUT, so "Vaier couldn't tell" must never read as "not
    // signed in" — that sends an operator to redo a sign-in nothing had undone.
    // `short` is the same standing said where the subject is already named — the panel's card is titled Claude,
    // and "Claude · Claude isn’t installed here" says it twice. One map, so the two surfaces can never drift
    // into two vocabularies.
    // `card` is the tint a machine card's mark wears and `tone` the tint of the same standing said in the shell
    // window, on this same map for the same reason. Only signed out has a card tint: a card speaks only of what
    // wants acting on, so a signed-in machine draws nothing. Both surfaces wear Claude's own clay rather than a
    // traffic light — a sign-in is presence, not an outage. Signed out is the same clay, hollow.
    const CLAUDE_STATE = {
        SIGNED_IN:     { label: 'Signed in',                   short: 'Signed in',     chip: 'Claude signed in',  tone: 'is-claude-in' },
        SIGNED_OUT:    { label: 'Signed out',                  short: 'Signed out',    chip: 'Claude signed out', tone: 'is-claude-out', card: 'is-claude-out' },
        NOT_INSTALLED: { label: 'Claude isn’t installed here', short: 'Not installed', chip: 'No Claude here',    tone: 'is-muted' },
        UNREACHABLE:   { label: 'Unreachable',                 short: 'Unreachable',   chip: 'Claude unreachable', tone: 'is-muted' },
        SKIPPED:       { label: 'No shell Vaier can reach',    short: 'No shell here', chip: 'No shell for Claude', tone: 'is-muted' },
        UNKNOWN:       { label: 'Couldn’t tell',               short: 'Couldn’t tell', chip: 'Claude unknown',    tone: 'is-muted' },
    };

    function words(state) {
        return CLAUDE_STATE[state] || { label: state, short: state, tone: 'is-muted' };
    }

    // Its own, because a module two pages load must not depend on either page's helpers.
    function el(tag, cls) {
        const made = document.createElement(tag);
        if (cls) made.className = cls;
        return made;
    }

    const path = (machineId, suffix) => '/machines/' + encodeURIComponent(machineId) + suffix;

    /**
     * Draw and drive the whole sign-in into `host`, for one machine.
     *
     * Returns a handle the host page uses for the two things it owns and this file cannot see: what the
     * standing looks like in its own chrome, and when the operator puts the panel away.
     */
    function mount(host, machineId, machineName) {
        const c = { phase: 'loading', status: null, error: '', msg: '',
                    flow: false, stage: '', url: '', code: '' };
        let reads = 0;
        let onUpdate = null;

        load();

        // --- what the host page is told ---------------------------------------------------------------

        // `draw` is a verdict actually taken: the server said a sign-in is impossible here, so there is
        // nothing to offer and a control explaining that would be worth less than no control. A read that
        // FAILED is not that verdict — it draws, saying Vaier could not tell.
        function standing() {
            if (c.phase === 'error') return { draw: true, state: 'UNKNOWN', title: c.error };
            const st = c.status;
            if (!st || !st.signInPossibleHere) return { draw: false };
            const who = st.effectiveUsername || 'the user Vaier logs in as';
            return { draw: true, state: st.state,
                     title: 'Claude — ' + words(st.state).label + ' for ' + who + ' on ' + machineName
                            + (st.accountEmail ? ' as ' + st.accountEmail : '') };
        }

        // --- reading where the machine stands ---------------------------------------------------------

        async function load() {
            const ticket = ++reads;
            c.phase = 'loading';
            render();
            try {
                const res = await fetch(path(machineId, '/claude-sign-in'), { cache: 'no-store' });
                if (!res.ok) throw new Error('read failed');
                const status = await res.json();
                if (ticket !== reads) return;
                c.status = status;
                c.phase = 'ready';
                c.error = '';
            } catch (e) {
                if (ticket !== reads) return;
                c.phase = 'error';
                // Said as a failed read, never as a verdict: a machine Vaier could not ask is not a machine
                // that is signed out, and the whole point of the UNKNOWN state is not to confuse the two.
                c.error = 'Vaier could not read where this machine stands on Claude. That is this read '
                    + 'failing, not a machine that is signed out.';
            }
            render();
        }

        // --- drawing ----------------------------------------------------------------------------------

        function render() {
            host.textContent = '';
            if (c.msg) {
                const m = el('div', 'tw-claude-msg');
                m.textContent = c.msg;
                host.appendChild(m);
            }
            if (c.phase === 'loading' && !c.status) {
                host.appendChild(card('Checking…', 'is-muted', '',
                    'Asking ' + machineName + ' whether Claude is signed in…', null));
            } else if (c.phase === 'error') {
                const failed = card('Couldn’t read', 'is-bad', '', c.error, null);
                failed.classList.add('is-error');
                host.appendChild(failed);
            } else if (c.status && c.status.signInPossibleHere) {
                host.appendChild(standingCard(c.status));
                if (c.flow) host.appendChild(flow());
            }
            if (onUpdate) onUpdate(standing());
        }

        // "Where this stands, and what to do about it", as one object: the thing, one word for how it
        // stands, the detail behind that word, and its verbs. Not a heading plus a paragraph plus a loose
        // row of buttons, which is three things to read for one answer.
        function card(stateText, tone, detail, why, actions) {
            const wrap = el('div', 'tw-claude-card');
            const text = el('div', 'tw-claude-text');

            const top = el('div', 'tw-claude-top');
            const t = el('span', 'tw-claude-name');
            t.textContent = 'Claude';
            top.appendChild(t);
            if (stateText) {
                const s = el('span', 'tw-claude-state ' + (tone || 'is-muted'));
                s.textContent = stateText;
                top.appendChild(s);
            }
            text.appendChild(top);

            if (detail) {
                const d = el('div', 'tw-claude-detail');
                d.textContent = detail;
                text.appendChild(d);
            }
            if (why) {
                const w = el('div', 'tw-claude-why');
                w.textContent = why;
                text.appendChild(w);
            }
            wrap.appendChild(text);
            if (actions) wrap.appendChild(actions);
            return wrap;
        }

        // Where this machine stands, said about the one user it is true of. The whole reason the user is
        // named: a sign-in lives in that user's home, so "signed in" here was once shown green while the
        // account actually running the machine's work was expired, with nothing on screen to reveal it.
        function standingCard(st) {
            const who = st.effectiveUsername || 'the user Vaier logs in as';
            const stand = words(st.state);
            let detail = '', why = '';
            if (st.state === 'SIGNED_IN') {
                detail = [st.accountEmail || 'an Anthropic account', st.subscriptionType, st.accountOrganisation]
                    .filter((x) => x).join(' · ');
                why = 'This is ' + who + '’s sign-in on ' + machineName + '; another user here signs in '
                    + 'separately.';
            } else if (st.state === 'SIGNED_OUT') {
                why = 'No sign-in for ' + who + ' here; another user may have their own.';
            } else if (st.state === 'NOT_INSTALLED') {
                why = 'Install it on ' + machineName + ' and this offers to sign in.';
            } else if (st.state === 'UNREACHABLE') {
                why = machineName + ' didn’t answer, so Vaier can’t say where it stands. It may be asleep.';
            } else {
                // UNKNOWN, and anything a newer backend adds. Never drawn as signed out.
                why = 'That is not the same as signed out — ' + machineName + ' answered with something '
                    + 'Vaier could not read.';
            }
            return card(stand.short, stand.tone, detail, why, verbs(st));
        }

        // Never offer an action Vaier already knows cannot work. No Claude, no answer at all, or nothing
        // there to sign out of means no button rather than a button that fails. UNKNOWN keeps one: Vaier
        // could not read that machine's answer, which is not the same as knowing there is nothing to sign in.
        function verbs(st) {
            const who = st.effectiveUsername || 'the user Vaier logs in as';
            const acts = el('div', 'tw-claude-acts');
            let any = false;

            // Signing in again is a real thing to want: it is how an account is changed, and how a credential
            // that has gone bad is replaced. Whether it may happen is the domain's answer, sent decided —
            // deriving it here from the state name is what left a signed-in machine with no way in.
            if (st.signInCanBegin) {
                const btn = el('button', 'tw-btn');
                btn.type = 'button';
                btn.textContent = st.state === 'SIGNED_IN' ? 'Sign in again' : 'Sign in';
                btn.title = 'Get an authorization URL from Claude on ' + machineName + ' and approve it in '
                    + 'your browser, signing in as ' + who + '.';
                if (c.flow) {
                    btn.disabled = true;
                    btn.title = 'This sign-in is open below.';
                }
                btn.onclick = start;
                acts.appendChild(btn);
                any = true;
            }
            if (st.state === 'SIGNED_IN') {
                const out = el('button', 'tw-btn');
                out.type = 'button';
                out.textContent = 'Sign out';
                out.title = 'Sign ' + who + ' out of Claude on ' + machineName + '.';
                out.onclick = () => signOut(st);
                acts.appendChild(out);
                any = true;
            }
            return any ? acts : null;
        }

        // Two stages of one job, both on screen at once. The ceremony this once had to avoid was doing it
        // machine after machine from a fleet page; in the shell window there is only ever this machine, so a
        // sheet over the terminal costs nothing and keeps the shell's rows out of it.
        function flow() {
            const wrap = el('div', 'tw-claude-flow');

            if (c.stage === 'starting') {
                const waiting = el('div', 'tw-claude-steptext');
                waiting.textContent = 'Asking Claude on ' + machineName + ' for an authorization URL…';
                wrap.append(waiting, flowActions(null));
                return wrap;
            }

            const finish = el('button', 'tw-btn tw-accent');
            finish.type = 'button';
            finish.textContent = c.stage === 'finishing' ? 'Signing in…' : 'Finish';
            finish.onclick = finishSignIn;

            // Both steps are on screen at once. Hiding the code box behind an "I have approved" click would
            // add a step that tells Vaier nothing it does anything with.
            wrap.appendChild(step(1, 'Open the approval page. You approve it there, on Anthropic’s own '
                + 'pages, and they show you a code to bring back.'));
            wrap.appendChild(urlRow(c.url));

            wrap.appendChild(step(2, 'Paste the code Anthropic showed you.'));
            const code = el('input', 'tw-input tw-claude-code');
            code.type = 'text';
            code.placeholder = 'Paste the code';
            code.autocomplete = 'off';
            code.spellcheck = false;
            code.value = c.code;
            code.disabled = c.stage === 'finishing';
            code.setAttribute('aria-label', 'The code Anthropic showed you for ' + machineName);
            const sync = () => { finish.disabled = c.stage === 'finishing' || code.value.trim() === ''; };
            code.oninput = () => { c.code = code.value; sync(); };
            code.onkeydown = (e) => {
                // The shell has the keyboard the moment this panel is not focused; a stray Enter here must
                // finish the sign-in, never fall through.
                e.stopPropagation();
                if (e.key === 'Enter' && !finish.disabled) finish.click();
            };
            sync();
            wrap.append(code, flowActions(finish));

            const hint = el('div', 'tw-claude-hint');
            hint.textContent = 'Closing this panel abandons the sign-in — the Claude CLI waiting on '
                + machineName + ' is ended rather than left at its prompt.';
            wrap.appendChild(hint);
            return wrap;
        }

        function step(number, text) {
            const row = el('div', 'tw-claude-step');
            const num = el('span', 'tw-claude-num');
            num.textContent = String(number);
            const body = el('span', 'tw-claude-steptext');
            body.textContent = text;
            row.append(num, body);
            return row;
        }

        // The authorization URL as a real link AND as text to copy: some operators click through, and some
        // paste it into the browser where they are already signed in to the right Anthropic account.
        // Opening the page is the step, so a button does it. It was a long line of URL text with Copy beside
        // it, which made the operator the transport: select, copy, find a tab, paste. An <a> rather than a
        // window.open, so it keeps what a link can do that a button cannot — middle-click, open in a new
        // window, copy the address — and so a popup blocker has nothing to block.
        //
        // Copy stays, quieter, because opening it HERE is not always what is wanted: the shell may be on a
        // laptop and the browser you are signed into Anthropic with on a phone. That is a real second case,
        // not a fallback for this one failing.
        function urlRow(url) {
            const wrap = el('div', 'tw-claude-url');

            const open = el('a', 'tw-btn tw-accent tw-claude-open');
            open.href = url;
            open.target = '_blank';
            open.rel = 'noopener noreferrer';
            open.textContent = 'Open the approval page';

            const copy = el('button', 'tw-btn');
            copy.type = 'button';
            copy.textContent = 'Copy the address';
            copy.onclick = () => {
                if (!navigator.clipboard) return say('Select the address and copy it by hand.');
                navigator.clipboard.writeText(url).then(() => say('Copied the authorization URL.'))
                    .catch(() => say('Could not copy. Select the address and copy it by hand.'));
            };

            const link = el('a', 'tw-claude-link');
            link.href = url;
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            link.textContent = url;
            link.title = url;

            wrap.append(open, copy, link);
            return wrap;
        }

        function flowActions(finish) {
            const acts = el('div', 'tw-claude-actions');
            if (finish) acts.appendChild(finish);
            const cancel = el('button', 'tw-btn');
            cancel.type = 'button';
            cancel.textContent = 'Cancel';
            cancel.title = 'End the sign-in waiting on ' + machineName + '.';
            cancel.onclick = () => abandon(false);
            acts.appendChild(cancel);
            return acts;
        }

        // --- the flow ---------------------------------------------------------------------------------

        async function start() {
            Object.assign(c, { flow: true, stage: 'starting', url: '', code: '', msg: '' });
            render();

            const started = await call(path(machineId, '/claude-sign-in'), 'POST', null,
                'Vaier could not start a sign-in on ' + machineName + '. Run claude in this shell to sign '
                    + 'in by hand — that always works.');
            // An answer with no URL in it is the same failure as no answer, and gets the same way out. The
            // server fails loudly rather than returning an empty one, so this is a belt against a shape
            // that changes.
            if (!started || !started.authorizationUrl) {
                if (started) {
                    say('Vaier started a sign-in on ' + machineName + ' but read no authorization URL back. '
                        + 'Run claude in this shell to sign in by hand.');
                }
                Object.assign(c, { flow: false, stage: '', url: '' });
                render();
                return;
            }
            Object.assign(c, { stage: 'open', url: started.authorizationUrl });
            render();
        }

        async function finishSignIn() {
            const code = (c.code || '').trim();
            if (!code) return;
            c.stage = 'finishing';
            render();

            const status = await call(path(machineId, '/claude-sign-in/code'), 'POST', { code: code },
                'Claude on ' + machineName + ' did not accept that code. A code expires within a few '
                    + 'minutes — cancel and start again for a fresh one.');
            // A rejected code leaves the flow open and the URL alive: the usual cause is a typo, and throwing
            // the URL away would make the operator start over for a mistake they can fix in place.
            if (!status) { c.stage = 'open'; render(); return; }

            Object.assign(c, { flow: false, stage: '', url: '', code: '' });
            // Where the machine actually landed, re-read from the machine rather than assumed from a 200.
            await load();
            say(status.state === 'SIGNED_IN'
                ? 'Signed ' + (status.effectiveUsername || machineName) + ' in on ' + machineName
                    + (status.accountEmail ? ' as ' + status.accountEmail : '') + '.'
                : 'Claude on ' + machineName + ' took the code but does not report itself signed in. This '
                    + 'panel says what it does report.');
        }

        // One deliberate click and no typed name: signing in again undoes it. The confirm still has to say
        // what it costs, and whose sign-in it is — signing out "this machine" when Vaier means one user on it
        // is a surprise waiting to happen.
        async function signOut(st) {
            const who = st.effectiveUsername || 'the user Vaier logs in as';
            const ok = await confirm('Sign ' + who + ' out of Claude on ' + machineName + '?',
                'Claude stops working for ' + who + ' on ' + machineName + ' until you sign in again. Any '
                + 'other user on that machine is untouched. Vaier deletes nothing — it runs the CLI’s own '
                + 'logout there — and signing back in takes a minute.',
                'Sign out');
            if (!ok) return;
            const status = await call(path(machineId, '/claude-sign-out'), 'POST', null,
                'Vaier could not sign ' + machineName + ' out. Check the machine is awake and reachable, '
                    + 'then try again.');
            if (!status) return;
            await load();
            say('Signed ' + who + ' out of Claude on ' + machineName + '.');
        }

        // The operator closed the flow. `quiet` is the same thing arriving from the other direction — they
        // put the panel away, which abandons it — and it must not re-read, because nothing is on screen to
        // show the answer to.
        async function abandon(quiet) {
            const wasOpen = c.flow;
            Object.assign(c, { flow: false, stage: '', url: '', code: '' });
            render();
            if (wasOpen) {
                try {
                    await fetch(path(machineId, '/claude-sign-in'), { method: 'DELETE' });
                } catch (e) {
                    // Nothing to say. It is already gone from this side, and the CLI on the machine gives
                    // up on its own.
                }
            }
            if (!quiet && wasOpen) await load();
        }

        // --- talking to Vaier -------------------------------------------------------------------------

        // The server's own message is the one shown whenever there is one. The domain writes the directive
        // copy for the failure that matters — which CLI output could not be read, and to open a terminal on
        // that machine instead — and a sentence invented here would only compete with it. `fallback` covers
        // what never reaches the domain: a Vaier that did not answer, a body that is not JSON.
        async function call(url, method, body, fallback) {
            try {
                const res = await fetch(url, body
                    ? { method: method, headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(body) }
                    : { method: method });
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    say(err.message || fallback);
                    return null;
                }
                return res.status === 204 ? true : await res.json().catch(() => true);
            } catch (e) {
                say(fallback);
                return null;
            }
        }

        // The panel's own line, rather than a toast: the operator is already looking here, and this window
        // has no overlay layer to float one over.
        function say(message) {
            c.msg = message;
            render();
        }

        // A confirmation the operator has to mean, built here because this window carries no dialog of its
        // own. Escape and the scrim both cancel.
        function confirm(title, bodyText, confirmLabel) {
            return new Promise((resolve) => {
                const scrim = el('div', 'tw-scrim');
                const dialog = el('div', 'tw-dialog');
                const h = el('div', 'tw-dialog-title');
                h.textContent = title;
                const b = el('div', 'tw-dialog-body');
                b.textContent = bodyText;   // never markup: it carries machine and user names
                const actions = el('div', 'tw-dialog-actions');
                const cancel = el('button', 'tw-btn');
                cancel.type = 'button';
                cancel.textContent = 'Cancel';
                const ok = el('button', 'tw-btn tw-accent');
                ok.type = 'button';
                ok.textContent = confirmLabel || 'Confirm';
                actions.append(cancel, ok);
                dialog.append(h, b, actions);
                scrim.appendChild(dialog);
                document.body.appendChild(scrim);

                const close = (result) => {
                    scrim.remove();
                    document.removeEventListener('keydown', onKey, true);
                    resolve(result);
                };
                const onKey = (e) => { e.stopPropagation(); if (e.key === 'Escape') close(false); };
                scrim.onclick = (e) => { if (e.target === scrim) close(false); };
                cancel.onclick = () => close(false);
                ok.onclick = () => close(true);
                // Captured, or the terminal underneath would eat every keystroke the dialog is asking for.
                document.addEventListener('keydown', onKey, true);
                ok.focus();
            });
        }

        return {
            // Called after every draw, with what the host page's own chrome should say about the standing.
            onUpdate: (cb) => { onUpdate = cb; cb(standing()); },
            // The operator put the panel away. That abandons a sign-in in progress, exactly as leaving the
            // machine used to — the CLI waiting on the far side is ended rather than left at its prompt.
            leave: () => { if (c.flow) abandon(true); },
        };
    }

    window.VaierClaude = { words: words, mount: mount };
})();
