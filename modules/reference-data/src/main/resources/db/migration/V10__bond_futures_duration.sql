-- Modified duration for Treasury futures — the rates sensitivity the scenario/stress
-- engine needs (quant-engine sequencing step 2): Δprice/price ≈ −D · Δy. CONVENTION:
-- demo values approximating each contract's cheapest-to-deliver duration (a real desk
-- derives D from the live CTD basket); they intentionally MATCH the sim's duration
-- link so scenario P&L and how sim prices actually move are the same model.

insert into instrument_attributes (instrument_id, name, value) values
    ('ZT', 'mod_duration', '1.9'),
    ('ZF', 'mod_duration', '4.2'),
    ('ZN', 'mod_duration', '6.3'),
    ('ZB', 'mod_duration', '17.0');
