/** Mocha root hooks: tear every rules test environment down once the run ends. */

const {cleanupAll} = require('./harness');

exports.mochaHooks = {
  async afterAll() {
    await cleanupAll();
  },
};
