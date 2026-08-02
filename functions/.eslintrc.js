module.exports = {
  env: {
    es6: true,
    node: true,
  },
  parserOptions: {
    'ecmaVersion': 2018,
  },
  extends: [
    'eslint:recommended',
    'google',
  ],
  rules: {
    'no-restricted-globals': ['error', 'name', 'length'],
    'prefer-arrow-callback': 'error',
    'quotes': ['error', 'single', {'allowTemplateLiterals': true}],
    'max-len': ['error', {'code': 120}],
    // Windows checkouts get CRLF via core.autocrlf; don't fail the functions
    // predeploy lint over line endings. `.gitattributes` keeps the repo copy LF.
    'linebreak-style': 'off',
  },
  overrides: [
    {
      files: ['**/*.spec.*', 'test/**/*.test.js'],
      env: {
        mocha: true,
      },
      rules: {
        // Test files import stubbing helpers (e.g. firebase-admin) that not
        // every spec in the file ends up using.
        'no-unused-vars': 'off',
      },
    },
  ],
  globals: {},
};

