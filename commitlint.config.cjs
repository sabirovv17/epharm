// Conventional Commits + scope-проверка для Epharm.
// Используется локально через .husky/commit-msg и в CI workflow `commitlint` job.

module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Допустимые типы коммитов
    'type-enum': [
      2,
      'always',
      ['feat', 'fix', 'chore', 'docs', 'refactor', 'test', 'ci', 'perf', 'build', 'style', 'revert'],
    ],
    // Допустимые scope'ы — модули экосистемы
    'scope-enum': [
      2,
      'always',
      ['admin', 'backend', 'mobile', 'posm', 'infra', 'repo', 'deps'],
    ],
    // Scope обязателен
    'scope-empty': [2, 'never'],
    // Заголовок не длиннее 72 символов
    'header-max-length': [2, 'always', 72],
    // Сабжект не оканчивается точкой
    'subject-full-stop': [2, 'never', '.'],
    // Сабжект в нижнем регистре (но допускаем "Spring", "React" — поэтому 'lower-case' выключаем)
    'subject-case': [0],
  },
}
