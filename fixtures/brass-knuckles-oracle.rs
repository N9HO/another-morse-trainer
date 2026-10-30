// Generator for fixtures/brass-knuckles.json. Not built by any workflow.
//
// To regenerate: take the game's source (github.com/N9HO/brass-knuckles-knock-out,
// source/logic, pocketcw-logic 0.4.0), append this module to src/boxing.rs, and run
// `cargo test oracle`. It writes /tmp/claude-0/bkg/oracle_out.json (change the path in
// `dump`); wrap that in the "$comment" and "derivation" keys of the fixture.
// Do it only when the rules are meant to change, and say so in the commit.
//
// Copyright the game's authors; GPL-3.0-or-later like the source it extends.

#[cfg(test)]
mod oracle {
    extern crate std;
    use super::*;
    use std::format;
    use std::string::{String, ToString};
    use std::vec::Vec;

    fn q(s: &str) -> Quality {
        match s {
            "good" => Quality::Good,
            "off" => Quality::Off,
            _ => Quality::Bad,
        }
    }

    fn ch(c: u8) -> String {
        (c as char).to_string()
    }

    fn pose_foe(p: Foe) -> String {
        format!("{:?}", p)
    }

    fn snap(f: &Fight, now: u32, played: &Option<Text<MAX_TELL>>) -> String {
        let phase = match f.phase {
            Phase::Over { won, how } => format!("Over:{}:{:?}", won, how),
            p => format!("{:?}", p),
        };
        let duel = match f.duel {
            Duel::Punch { dodged } => format!("Punch:{}", dodged),
            d => format!("{:?}", d),
        };
        let callout = match f.callout {
            None => "-".to_string(),
            Some((Callout::Wrong { sent }, _)) => format!("Wrong:{}", sent as char),
            Some((c, _)) => format!("{:?}", c),
        };
        let s = &f.stats;
        format!(
            "{}|{}|hp {} {}|st {} run {}|sc {}|rd {}|now {}|dl {}|tell {}|ans {}|word {}|typed {}|cnt {}|up {}|play {}|stats {},{},{},{},{},{},{},{},{}|foe {}|me {}|call {}|stop {}",
            phase,
            duel,
            f.foe_hp,
            f.my_hp,
            f.stars,
            f.clean_run,
            f.score,
            f.round,
            now,
            f.deadline().map_or("-".to_string(), |d| d.to_string()),
            f.tell.as_str(),
            f.answer.as_str(),
            f.word.as_str(),
            f.typed.as_str(),
            f.count,
            ch(f.get_up),
            played.map_or("-".to_string(), |t| t.as_str().to_string()),
            s.tells, s.dodged, s.punches, s.clean, s.damage, s.taken, s.uppercuts, s.knockdowns, s.knocked,
            pose_foe(f.foe_pose(now).0),
            format!("{:?}", f.me_pose(now).0),
            callout,
            f.stop,
        )
    }

    type Op = &'static [&'static str];

    fn run(fighter: usize, seed: u32, hints: bool, dit: u16, call: &str, ops: &[Op]) -> Vec<String> {
        let mut f = Fight::new(fighter, seed, hints, dit, call);
        let mut now: u32 = 1000;
        let mut out = Vec::new();
        for op in ops {
            let n = |i: usize| -> u32 { op.get(i).map_or(0, |s| s.parse::<i64>().unwrap() as u32) };
            match op[0] {
                "start" => assert!(f.start(now)),
                "startFalse" => assert!(!f.start(now)),
                "advance" => {
                    if let Some(d) = f.deadline() {
                        now = d;
                        f.update(now);
                    }
                }
                "update" => {
                    now = now.wrapping_add(n(1));
                    f.update(now);
                }
                "tellPlayed" => {
                    now = now.wrapping_add(n(1));
                    f.tell_played(now);
                }
                "answer" => {
                    now = now.wrapping_add(n(2));
                    let tell = f.tell;
                    for c in tell.as_str().bytes() {
                        f.key(c, q(op[1]), now);
                    }
                }
                "key" => {
                    now = now.wrapping_add(n(3));
                    f.key(op[1].as_bytes()[0], q(op[2]), now);
                }
                "getUp" => {
                    now = now.wrapping_add(n(2));
                    let c = f.get_up;
                    f.key(c, q(op[1]), now);
                }
                "keyUnknown" => {
                    now = now.wrapping_add(n(1));
                    f.key_unknown(now);
                }
                "star" => {
                    now = now.wrapping_add(n(1));
                    f.star(now);
                }
                "word" => {
                    now = now.wrapping_add(n(2));
                    let w = f.word;
                    for c in w.as_str().bytes() {
                        f.key(c, q(op[1]), now);
                    }
                }
                "pause" => {
                    now = now.wrapping_add(n(1));
                    f.pause(now);
                }
                "resume" => {
                    now = now.wrapping_add(n(1));
                    f.resume(now);
                }
                "setFoeHp" => f.foe_hp = n(1) as i16,
                "setMyHp" => f.my_hp = n(1) as i16,
                "setStars" => f.stars = n(1) as u8,
                "wait" => now = now.wrapping_add(n(1)),
                other => panic!("op {other}"),
            }
            let played = f.take_play();
            let mut line = snap(&f, now, &played);
            let mut copies = Vec::new();
            f.take_copies(|s, c| copies.push(format!("{}{}", s as char, c.map_or("_".to_string(), |c| ch(c)))));
            line.push_str(&format!("|copies {}", copies.join(",")));
            out.push(line);
        }
        out
    }

    fn json_str(s: &str) -> String {
        format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
    }

    struct Scn {
        name: &'static str,
        fighter: usize,
        seed: u32,
        hints: bool,
        dit: u16,
        call: &'static str,
        ops: &'static [Op],
    }

    const SCENARIOS: &[Scn] = &[
        Scn { name: "dodge_and_counter", fighter: 0, seed: 7, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "500"], &["answer", "good", "200"],
            &["advance"], &["key", "E", "good", "100"], &["key", "K", "off", "800"], &["advance"], &["key", "T", "good", "10"],
            &["advance"], &["advance"],
        ]},
        Scn { name: "wrong_answer_then_too_slow", fighter: 0, seed: 3, hints: false, dit: 60, call: "N9HO", ops: &[
            &["start"], &["advance"], &["advance"], &["key", "Z", "good", "300"], &["advance"], &["advance"],
            &["advance"], &["tellPlayed", "400"], &["advance"], &["advance"], &["advance"], &["keyUnknown", "0"],
        ]},
        Scn { name: "star_and_uppercut", fighter: 1, seed: 9, hints: true, dit: 80, call: "K5MME", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "300"], &["answer", "good", "100"], &["advance"],
            &["key", "S", "good", "100"], &["key", "S", "good", "100"], &["key", "S", "good", "100"], &["star", "100"],
            &["word", "good", "500"], &["star", "100"], &["advance"], &["advance"],
        ]},
        Scn { name: "star_wasted_by_a_wrong_character", fighter: 1, seed: 5, hints: true, dit: 80, call: "K5MME", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "300"], &["answer", "good", "100"], &["advance"],
            &["setStars", "1"], &["star", "0"], &["key", "#", "good", "100"], &["keyUnknown", "50"], &["advance"],
        ]},
        Scn { name: "three_knockdowns_tko", fighter: 0, seed: 7, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"],
            &["advance"], &["tellPlayed", "400"], &["answer", "good", "100"], &["advance"], &["setFoeHp", "1"], &["key", "E", "good", "50"],
            &["advance"], &["advance"], &["advance"], &["advance"],
            &["advance"], &["tellPlayed", "400"], &["answer", "good", "100"], &["advance"], &["setFoeHp", "1"], &["key", "E", "good", "50"],
            &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"],
            &["advance"], &["tellPlayed", "400"], &["answer", "good", "100"], &["advance"], &["setFoeHp", "1"], &["key", "E", "good", "50"],
            &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"],
        ]},
        Scn { name: "knocked_down_then_get_up", fighter: 3, seed: 11, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"], &["setMyHp", "1"], &["advance"], &["key", "Z", "good", "300"], &["advance"], &["advance"],
            &["keyUnknown", "20"], &["advance"], &["getUp", "bad", "20"], &["advance"], &["advance"],
        ]},
        Scn { name: "staying_down_loses_by_ko", fighter: 0, seed: 2, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"], &["setMyHp", "1"], &["advance"], &["tellPlayed", "10"], &["advance"], &["advance"],
            &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"], &["advance"],
            &["advance"], &["advance"],
        ]},
        Scn { name: "the_bell_and_the_decision", fighter: 0, seed: 7, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "300"], &["answer", "good", "100"], &["advance"],
            &["key", "S", "good", "50"], &["update", "90000"], &["startFalse"], &["advance"], &["start"], &["advance"],
            &["update", "90000"], &["advance"], &["start"], &["advance"], &["update", "90000"],
        ]},
        Scn { name: "pause_stops_every_clock", fighter: 0, seed: 7, hints: true, dit: 60, call: "K5MME", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "0"], &["pause", "100"], &["update", "50000"],
            &["resume", "10000"], &["advance"], &["advance"], &["pause", "10"], &["resume", "10"],
        ]},
        Scn { name: "pausing_mid_tell_takes_it_back", fighter: 4, seed: 21, hints: false, dit: 50, call: "W1AW", ops: &[
            &["start"], &["advance"], &["advance"], &["pause", "100"], &["resume", "4900"], &["tellPlayed", "100"],
            &["advance"], &["advance"], &["tellPlayed", "100"], &["answer", "bad", "200"], &["advance"], &["advance"],
        ]},
        Scn { name: "last_fighter_long_tells", fighter: 7, seed: 99, hints: true, dit: 40, call: "AB1CD", ops: &[
            &["start"], &["advance"], &["advance"], &["tellPlayed", "300"], &["answer", "off", "100"], &["advance"], &["advance"],
            &["advance"], &["advance"], &["tellPlayed", "300"], &["answer", "good", "100"], &["advance"], &["key", "Q", "bad", "100"],
            &["advance"], &["advance"], &["advance"],
        ]},
    ];

    #[test]
    fn dump() {
        let mut j = String::new();
        j.push_str("{\n");
        // The ladder.
        j.push_str("\"ladder\": [\n");
        let rows: Vec<String> = LADDER.iter().map(|f| format!(
            "  [{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}]",
            json_str(f.name), json_str(f.from), json_str(f.blurb), f.koch, f.speed_bump, f.spacing_pct, f.hint, f.noise, f.fading,
            f.max_tell, f.hp, f.power, f.react_ms, f.rest_ms.0, f.rest_ms.1, f.open_ms, f.pitch_offset_hz, f.fist
        ).replace("true", "true")).collect();
        j.push_str(&rows.join(",\n"));
        j.push_str("\n],\n");
        // Speeds.
        let mut sp = Vec::new();
        for (i, f) in LADDER.iter().enumerate() {
            for (base, fw) in [(20u8, 0u8), (20, 15), (5, 0), (58, 0)] {
                let (w, e) = f.speeds(base, fw);
                sp.push(format!("[{}, {}, {}, {}, {}]", i, base, fw, w, e));
            }
        }
        j.push_str(&format!("\"speeds\": [{}],\n", sp.join(", ")));
        // Punch kinds, damage and units.
        let mut pu = Vec::new();
        let chars = b"KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X";
        for &c in chars.iter() {
            let kind = match punch_of(c) {
                Some(Punch::Jab) => "jab".to_string(),
                Some(Punch::Hook) => "hook".to_string(),
                Some(Punch::Combo(n)) => format!("combo{}", n),
                None => "none".to_string(),
            };
            pu.push(format!(
                "[{}, {}, {}, {}, {}, {}]",
                json_str(&ch(c)), json_str(&kind), units(c),
                damage(c, Quality::Good), damage(c, Quality::Off), damage(c, Quality::Bad)
            ));
        }
        j.push_str(&format!("\"characters\": [{}],\n", pu.join(", ")));
        j.push_str(&format!("\"noPunch\": {},\n", json_str("#")));
        // The RNG.
        let mut rng = Rng::new(1);
        let seq: Vec<String> = (0..16).map(|_| rng.below(100).to_string()).collect();
        let mut rng2 = Rng::new(0xDEADBEEF);
        let seq2: Vec<String> = (0..16).map(|_| rng2.below(1000).to_string()).collect();
        let mut rng3 = Rng::new(1);
        let calls: Vec<String> = (0..24).map(|k| json_str(us_call((k % 10) as u8, &mut rng3).as_str())).collect();
        j.push_str(&format!("\"rng\": {{ \"seed1Below100\": [{}], \"seedDEADBEEFBelow1000\": [{}], \"usCallsSeed1\": [{}] }},\n", seq.join(", "), seq2.join(", "), calls.join(", ")));
        // Tells and words per fighter.
        let mut tw = Vec::new();
        for k in 0..FIGHTERS {
            let mut f = Fight::new(k, 12345, true, 60, "K5MME");
            let tells: Vec<String> = (0..12).map(|_| json_str(f.pick_tell().as_str())).collect();
            let mut g = Fight::new(k, 777, true, 60, "K5MME");
            let words: Vec<String> = (0..10).map(|_| json_str(g.pick_word().as_str())).collect();
            tw.push(format!("  {{ \"fighter\": {}, \"tells\": [{}], \"words\": [{}] }}", k, tells.join(", "), words.join(", ")));
        }
        j.push_str(&format!("\"tellsAndWords\": [\n{}\n],\n", tw.join(",\n")));
        // Career.
        let mut c = Career::default();
        let mut cr = Vec::new();
        for (fi, won, sc) in [(0usize, false, 1234u32), (0, true, 900), (0, true, 99_999), (1, true, 5000), (3, true, 10), (1, true, 7)] {
            let opens = c.finished(fi, won, sc);
            cr.push(format!("[{}, {}, {}, {}, {}, {}, {}]", fi, won, sc, opens, c.unlocked, c.best_score(fi), c.wins[fi]));
        }
        j.push_str(&format!("\"career\": [{}],\n", cr.join(", ")));
        // Fight stats worst-first.
        let mut s = FightStats::default();
        s.count(b'K', true); s.count(b'K', true); s.count(b'M', false); s.count(b'R', true); s.count(b'R', false);
        let worst: Vec<String> = s.worst().map(|w| format!("[{}, {}, {}]", json_str(&ch(w.0)), w.1, w.2)).collect();
        j.push_str(&format!("\"worst\": [{}],\n", worst.join(", ")));
        // Scripted fights.
        j.push_str("\"fights\": [\n");
        let mut fights = Vec::new();
        for s in SCENARIOS {
            let ops: Vec<String> = s.ops.iter().map(|o| format!("[{}]", o.iter().map(|x| json_str(x)).collect::<Vec<_>>().join(", "))).collect();
            let snaps: Vec<String> = run(s.fighter, s.seed, s.hints, s.dit, s.call, s.ops).iter().map(|x| json_str(x)).collect();
            fights.push(format!(
                "  {{ \"name\": {}, \"fighter\": {}, \"seed\": {}, \"hints\": {}, \"ditMs\": {}, \"callsign\": {},\n    \"ops\": [{}],\n    \"expect\": [\n      {}\n    ] }}",
                json_str(s.name), s.fighter, s.seed, s.hints, s.dit, json_str(s.call), ops.join(", "), snaps.join(",\n      ")
            ));
        }
        j.push_str(&fights.join(",\n"));
        j.push_str("\n]\n}\n");
        std::fs::write("/tmp/claude-0/bkg/oracle_out.json", j).unwrap();
    }
}
