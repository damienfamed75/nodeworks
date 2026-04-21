-- Pull every item from the "input" container and push it into "output".
-- Runs on every `network:onInsert` tick so the moment something lands in
-- input, it gets shuttled over.
local input = network:get("input")
local output = network:get("output")

local all = input:find("*")
if all then
  output:insert(all)
end
