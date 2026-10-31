-- The booth terminal a manager answers for.
--
-- A manager with a terminal acts as it: what they hand over is recorded under the
-- terminal's id, so the organiser reads one name per counter whether the tablet or the
-- manager's phone read the code, and the manager can make a device that terminal
-- without a setup code. Deleting the terminal leaves the manager in place, unassigned.
ALTER TABLE booth_manager ADD COLUMN gate_id VARCHAR(32) REFERENCES gate(id) ON DELETE SET NULL;

-- The console's terminal cards ask which managers each one has.
CREATE INDEX idx_booth_manager_gate ON booth_manager(gate_id);
