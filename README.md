# rooty-toots

Simple, useful handlers for the rooty-tooty framework.

## DnsHandler
Manages a djbdns (aka tinydns) data file. Accepts add/remove messages to update file contents, then reloads configuration (via `svc -h /path/to/tinydns`)

## PostfixHandler
Manages a postfix installation. Supports:

* Add account
* Remove account
* Add domain
* Remove domain

For simplicity, when adding a user, they are automatically added to all domains. Likewise, when adding a domain, all users will become members of the domain.

## ChefHandler
Manages chef cookbooks and runs chef-solo. Supports:

* Add recipe to run-list
* Remove recipe from run-list